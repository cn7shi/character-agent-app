package com.character.app

import com.character.app.domain.CharacterRepository
import com.character.app.domain.MessageDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** D26: one local writer, independent of network-session cancellation. All calls use the UI dispatcher. */
internal class DraftWriter(
    scope: CoroutineScope,
    private val onResult: (Key, MessageDraft, Boolean) -> Unit,
) {
    data class Key(val profile: String, val conversation: String)
    private data class Write(val repository: CharacterRepository, val draft: MessageDraft)
    private sealed interface Command {
        data class Save(val key: Key) : Command
        data class Flush(val key: Key, val result: CompletableDeferred<Boolean>) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pending = mutableMapOf<Key, Write>()
    private val failures = mutableSetOf<Key>()

    init {
        scope.launch {
            for (command in commands) when (command) {
                is Command.Save -> {
                    val write = pending.remove(command.key) ?: continue
                    try {
                        write.repository.saveDraft(command.key.conversation, write.draft)
                        failures.remove(command.key)
                        onResult(command.key, write.draft, true)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failures.add(command.key)
                        onResult(command.key, write.draft, false)
                    }
                }
                is Command.Flush -> {
                    command.result.complete(command.key !in failures)
                }
            }
        }
    }

    fun save(key: Key, repository: CharacterRepository, draft: MessageDraft) {
        // At most one queued Save per conversation; rapid edits replace its value, not grow a text queue.
        val alreadyQueued = pending.put(key, Write(repository, draft)) != null
        if (!alreadyQueued) check(commands.trySend(Command.Save(key)).isSuccess)
    }

    suspend fun flush(key: Key): Boolean {
        // A barrier drains older writes before outbox handoff, preventing a late save resurrecting a sent draft.
        val result = CompletableDeferred<Boolean>()
        commands.send(Command.Flush(key, result))
        return result.await()
    }

    fun close() { commands.close() } // Drain accepted edits on ordinary ViewModel disposal.
}
