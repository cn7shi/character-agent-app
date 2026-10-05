package com.character.app.data

import androidx.room.withTransaction
import com.character.app.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

fun userMessage(error: Throwable): String = when (error) {
    is BackendFailure -> when (error.kind) {
        FailureKind.AUTHENTICATION, FailureKind.FORBIDDEN -> "连接凭证无效，请在设置中检查。"
        FailureKind.NOT_FOUND -> "记录不存在或不属于当前连接。"
        FailureKind.CONFLICT -> "会话正忙或请求冲突，请刷新后再试。"
        FailureKind.INVALID_REQUEST -> "内容不符合要求，请检查后重新发送。"
        FailureKind.THROTTLED -> "请求较多，请稍后重试。"
        FailureKind.PROTOCOL, FailureKind.UNSUPPORTED -> "服务返回了无法识别的数据，请检查服务版本。"
        else -> "暂时无法连接服务。已缓存内容仍可查看，请稍后重试。"
    }
    is IllegalArgumentException -> "请检查输入内容或连接设置。"
    is IllegalStateException -> "请先处理当前待发送消息或等待回复结束。"
    else -> "暂时无法完成操作，请重试。"
}

class OfflineRepository(private val db: ClientDatabase, private val scope: String, private val remote: BackendAdapter) : CharacterRepository {
    private val dao = db.dao()
    private val gate = Mutex()
    private fun <T> watch(kind: String, read: (JSONObject) -> T) = dao.observe(scope, kind).map { rows -> rows.map { read(JSONObject(it.payload)) } }
    override fun characters() = watch("characters", JsonCodec::character)
    override fun conversations() = watch("conversations", JsonCodec::conversation)
    override fun messages(conversationId: String) = watch("messages:$conversationId", JsonCodec::message).map { it.sortedBy(Message::sequence) }
    override fun runs(conversationId: String) = watch("runs:$conversationId", JsonCodec::run)
    override fun notes() = watch("notes", JsonCodec::note)
    override fun pending(conversationId: String) = dao.observePending(scope, conversationId).map { rows -> rows.map { PendingMessage(it.id, it.conversationId, it.text, it.error, it.blocked) } }

    private fun row(c: Character) = Snapshot(scope, "characters", c.id, JsonCodec.encode(c).toString(), c.name)
    private fun row(c: Conversation) = Snapshot(scope, "conversations", c.id, JsonCodec.encode(c).toString(), c.updatedAt)
    private fun row(m: Message) = Snapshot(scope, "messages:${m.conversationId}", m.id, JsonCodec.encode(m).toString(), m.sequence.toString().padStart(20, '0'))
    private fun row(n: Note) = Snapshot(scope, "notes", n.id, JsonCodec.encode(n).toString(), n.createdAt)

    private suspend fun <T> allPages(fetch: suspend (String?) -> Page<T>): List<T> {
        val result = mutableListOf<T>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = fetch(cursor)
            result += page.items
            cursor = page.nextCursor
            check(cursor == null || (seen.add(cursor) && seen.size < 1000))
        } while (cursor != null)
        return result
    }
    override suspend fun refreshHome() = gate.withLock {
        dao.put(allPages(remote::characters).map(::row))
        dao.put(allPages(remote::conversations).map(::row))
    }
    override suspend fun refreshNotes() = gate.withLock { refreshNotesLocked() }
    private suspend fun refreshNotesLocked() { dao.put(allPages(remote::notes).map(::row)) }

    override suspend fun refreshConversation(conversationId: String) = gate.withLock {
        val messages = allPages { remote.messages(conversationId, it) }
        db.withTransaction {
            for (message in messages) {
                val existing = dao.get(scope, "messages:$conversationId", message.id)?.let { JsonCodec.message(JSONObject(it.payload)) }
                if (existing == null || existing.updatedAt <= message.updatedAt) dao.put(listOf(row(message)))
            }
        }
        // After process death, pending/streaming messages identify tasks to resume observing, never recreate.
        for (id in messages.filter { it.status in setOf("pending", "streaming") }.map { it.runId }.distinct()) store(remote.run(id))
        pollLocked(conversationId)
    }

    override suspend fun createConversation(characterId: String): String = gate.withLock {
        val pending = dao.conversationRequest(scope, characterId) ?: ConversationRequest(scope, characterId, UUID.randomUUID().toString()).also { dao.putConversationRequest(it) }
        val conversation = remote.createConversation(characterId, pending.requestKey)
        db.withTransaction {
            dao.put(listOf(row(conversation)))
            dao.deleteConversationRequest(scope, characterId)
        }
        conversation.id
    }

    override suspend fun send(conversationId: String, text: String) = gate.withLock {
        val normalized = text.trim()
        require(normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= 8000)
        check(dao.pending(scope, conversationId).isEmpty())
        check(dao.list(scope, "runs:$conversationId").none { !JsonCodec.run(JSONObject(it.payload)).terminal })
        val pending = Outbox(scope, UUID.randomUUID().toString(), conversationId, UUID.randomUUID().toString(), normalized)
        // D20: persist both IDs BEFORE HTTP; ambiguous failures reuse this exact request after restart.
        dao.insertPending(pending)
        submit(pending)
    }
    override suspend fun retry(messageId: String) = gate.withLock {
        val pending = checkNotNull(dao.pendingById(scope, messageId))
        check(!pending.blocked)
        // Older app versions may have left a null error during process death; a manual retry is always a replay.
        submit(pending.copy(error = pending.error ?: "上次提交结果待确认。"))
    }
    override suspend fun dismiss(messageId: String) = gate.withLock {
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
            db.withTransaction { store(run); dao.deletePending(scope, pending.id) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A non-retryable protocol error may follow an accepted write. Only confirmed rejection is dismissible.
            dao.updatePending(pending.copy(error = userMessage(error), blocked = pending.error == null && error is BackendFailure && error.definitiveRejection))
            throw error
        }
    }
    internal suspend fun store(run: Run) = db.withTransaction {
        val kind = "runs:${run.conversationId}"
        val old = dao.get(scope, kind, run.id)?.let { JsonCodec.run(JSONObject(it.payload)) }
        if (run.sequence < 0 || run.input.runId != run.id || run.output.runId != run.id ||
            run.input.conversationId != run.conversationId || run.output.conversationId != run.conversationId)
            throw BackendFailure(FailureKind.PROTOCOL)
        // D23: this sequence is the durable SSE cursor; both messages and run commit together.
        if (old == null || (run.sequence > old.sequence && !old.terminal)) {
            dao.put(listOf(row(run.input), row(run.output), Snapshot(scope, kind, run.id, JsonCodec.encode(run).toString(), run.output.updatedAt)))
        }
    }
    override suspend fun poll(conversationId: String) = gate.withLock { pollLocked(conversationId) }
    private suspend fun pollLocked(conversationId: String) {
        val active = dao.list(scope, "runs:$conversationId").map { JsonCodec.run(JSONObject(it.payload)) }.filterNot { it.terminal }
        for (run in active) {
            val current = remote.run(run.id)
            store(current)
            if (current.terminal) refreshNotesLocked()
        }
    }
    // Observation/network retries must not hold the submission mutex and delay an explicit stop.
    override suspend fun cancel(runId: String) { store(remote.cancel(runId)) }

    override suspend fun observeConversation(conversationId: String) {
        refreshConversation(conversationId)
        refreshNotes() // Also reconciles a tool committed just before process death or observation cancellation.
        val monitor = RunMonitor(remote)
        while (true) {
            currentCoroutineContext().ensureActive()
            val active = runs(conversationId).first { list -> list.any { !it.terminal } }.first { !it.terminal }
            monitor.follow(load = {
                checkNotNull(dao.get(scope, "runs:$conversationId", active.id)).let { JsonCodec.run(JSONObject(it.payload)) }
            }, save = ::store)
            refreshNotes()
        }
    }
}
