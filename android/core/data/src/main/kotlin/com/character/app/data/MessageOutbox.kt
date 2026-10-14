package com.character.app.data

import androidx.room.withTransaction
import com.character.app.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * MessageOutbox
 *
 * Dedicated component managing the transactional Outbox pattern for asynchronous message submission,
 * retry, dismissal, run persistence, and offline durability.
 *
 * Decoupled from OfflineRepository to satisfy Single Responsibility and Clean Architecture.
 */
internal class MessageOutbox(
    private val db: ClientDatabase,
    private val scope: String,
    private val remote: BackendAdapter
) {
    private val dao = db.dao()
    private val gate = Mutex()

    fun pending(conversationId: String): Flow<List<PendingMessage>> =
        dao.observePending(scope, conversationId).map { rows ->
            rows.map { PendingMessage(it.id, it.conversationId, it.text, it.error, it.blocked) }
        }

    suspend fun send(conversationId: String, text: String, draftRevision: String?) = gate.withLock {
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= 8000)
        check(dao.pending(scope, conversationId).isEmpty())
        check(dao.list(scope, "runs:$conversationId").none { !JsonCodec.run(JSONObject(it.payload)).terminal })
        val pending = Outbox(scope, UUID.randomUUID().toString(), conversationId, UUID.randomUUID().toString(), normalized)
        // D20: persist both IDs BEFORE HTTP; ambiguous failures reuse this exact request after restart.
        db.withTransaction {
            dao.insertPending(pending)
            // D26: process death leaves either a draft or the original outbox identity, never a split handoff.
            if (draftRevision != null) dao.consumeDraft(scope, conversationId, draftRevision)
        }
        submit(pending)
    }

    suspend fun retry(messageId: String) = gate.withLock {
        val pending = checkNotNull(dao.pendingById(scope, messageId))
        check(!pending.blocked)
        // Older app versions may have left a null error during process death; a manual retry is always a replay.
        submit(pending.copy(error = pending.error ?: "上次提交结果待确认。"))
    }

    suspend fun dismiss(messageId: String) = gate.withLock {
        val pending = checkNotNull(dao.pendingById(scope, messageId))
        // Unknown acceptance must be reconciled using the original key, never silently discarded.
        check(pending.blocked)
        dao.deletePending(scope, messageId)
    }

    private suspend fun submit(pending: Outbox) {
        // Persist BEFORE I/O: after cancellation/process death a later rejection cannot disprove prior acceptance.
        dao.updatePending(pending.copy(error = "正在确认发送结果，请勿重复发送。"))
        try {
            val run = remote.createRun(pending.conversationId, pending.id, pending.requestKey, pending.text)
            db.withTransaction {
                store(run)
                dao.deletePending(scope, pending.id)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A non-retryable protocol error may follow an accepted write. Only confirmed rejection is dismissible.
            dao.updatePending(
                pending.copy(
                    error = userMessage(error),
                    blocked = pending.error == null && error is BackendFailure && error.definitiveRejection
                )
            )
            throw error
        }
    }

    suspend fun store(run: Run) = db.withTransaction {
        val kind = "runs:${run.conversationId}"
        val old = dao.get(scope, kind, run.id)?.let { JsonCodec.run(JSONObject(it.payload)) }
        if (run.sequence < 0 || run.input.runId != run.id || run.output.runId != run.id ||
            run.input.conversationId != run.conversationId || run.output.conversationId != run.conversationId
        ) {
            throw BackendFailure(FailureKind.PROTOCOL)
        }
        // D23: this sequence is the durable SSE cursor; both messages and run commit together.
        if (old == null || (run.sequence > old.sequence && !old.terminal)) {
            dao.put(
                listOf(
                    row(run.input),
                    row(run.output),
                    Snapshot(scope, kind, run.id, JsonCodec.encode(run).toString(), run.output.updatedAt)
                )
            )
        }
    }

    private fun row(m: Message) =
        Snapshot(scope, "messages:${m.conversationId}", m.id, JsonCodec.encode(m).toString(), m.sequence.toString().padStart(20, '0'))
}
