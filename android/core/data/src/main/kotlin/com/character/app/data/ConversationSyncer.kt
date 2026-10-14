package com.character.app.data

import androidx.room.withTransaction
import com.character.app.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * ConversationSyncer
 *
 * Dedicated component coordinating conversation message synchronization, active run polling,
 * run cancellation, and real-time SSE streaming observation via [RunMonitor].
 *
 * Decoupled from OfflineRepository to satisfy Single Responsibility and Clean Architecture.
 */
internal class ConversationSyncer(
    private val db: ClientDatabase,
    private val scope: String,
    private val remote: BackendAdapter,
    private val storeRun: suspend (Run) -> Unit,
    private val onNotesUpdated: suspend () -> Unit
) {
    private val dao = db.dao()
    private val syncGate = Mutex()

    suspend fun refreshConversation(conversationId: String) = syncGate.withLock {
        val messages = fetchAllPages { remote.messages(conversationId, it) }
        db.withTransaction {
            for (message in messages) {
                val existing = dao.get(scope, "messages:$conversationId", message.id)?.let {
                    JsonCodec.message(JSONObject(it.payload))
                }
                if (existing == null || existing.updatedAt <= message.updatedAt) {
                    dao.put(listOf(row(message)))
                }
            }
        }
        // After process death, pending/streaming messages identify tasks to resume observing, never recreate.
        for (id in messages.filter { it.status in setOf("pending", "streaming") }.map { it.runId }.distinct()) {
            storeRun(remote.run(id))
        }
        pollLocked(conversationId)
    }

    suspend fun poll(conversationId: String) = syncGate.withLock {
        pollLocked(conversationId)
    }

    private suspend fun pollLocked(conversationId: String) {
        val active = dao.list(scope, "runs:$conversationId")
            .map { JsonCodec.run(JSONObject(it.payload)) }
            .filterNot { it.terminal }
        for (run in active) {
            val current = remote.run(run.id)
            storeRun(current)
            if (current.terminal) onNotesUpdated()
        }
    }

    // Observation/network retries must not hold the submission mutex and delay an explicit stop.
    suspend fun cancel(runId: String) {
        storeRun(remote.cancel(runId))
    }

    suspend fun observeConversation(
        conversationId: String,
        runsFlow: Flow<List<Run>>
    ) {
        refreshConversation(conversationId)
        onNotesUpdated() // Also reconciles a tool committed just before process death or observation cancellation.
        val monitor = RunMonitor(remote)
        // D25: a cancel response can commit terminal state while the event socket stays silent.
        // Observe the active ID so Room cancels that socket and starts the next run independently.
        runsFlow.map { list -> list.firstOrNull { !it.terminal }?.id }
            .distinctUntilChanged().collectLatest { activeId ->
                if (activeId == null) {
                    onNotesUpdated()
                } else {
                    monitor.follow(
                        load = {
                            checkNotNull(dao.get(scope, "runs:$conversationId", activeId)).let {
                                JsonCodec.run(JSONObject(it.payload))
                            }
                        },
                        save = storeRun
                    )
                }
            }
    }

    private fun row(m: Message) =
        Snapshot(scope, "messages:${m.conversationId}", m.id, JsonCodec.encode(m).toString(), m.sequence.toString().padStart(20, '0'))
}

internal suspend fun <T> fetchAllPages(fetch: suspend (String?) -> Page<T>): List<T> {
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
