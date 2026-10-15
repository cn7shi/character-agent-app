package com.character.app.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/**
 * Universal run execution monitor.
 * Applies the same event recovery policy across any backend adapter.
 * Persistence owns the acknowledged cursor.
 */
class RunMonitor(
    private val backend: BackendAdapter,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val streamAttempts: Int = 3,
) {
    init {
        require(streamAttempts in 1..10) { "streamAttempts must be in 1..10" }
    }

    suspend fun follow(load: suspend () -> Run, save: suspend (Run) -> Unit) {
        var streaming = backend.supportsEvents
        var failures = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val current = load()
            if (current.terminal) return

            if (!streaming) {
                pause(1_000)
                reconcile(current, save)
                continue
            }

            try {
                backend.events(current.id, current.sequence).takeWhile { event ->
                    val stored = load()
                    if (stored.terminal) return@takeWhile false

                    if (event.runId != stored.id || event.sequence < 1) {
                        throw BackendFailure(FailureKind.PROTOCOL)
                    }
                    if (event.sequence <= stored.sequence) {
                        return@takeWhile true // Duplicate replay is harmless.
                    }

                    if (event.sequence != stored.sequence + 1 || event.kind == EventKind.UNKNOWN) {
                        reconcile(stored, save, event.sequence)
                    } else {
                        // Message + cursor must commit atomically.
                        save(applyEvent(stored, event))
                    }
                    !load().terminal
                }.collect()

                if (load().terminal) return
                // EOF without terminal run status is considered a connection drop.
                throw BackendFailure(FailureKind.CONNECTION)
            } catch (failure: BackendFailure) {
                if (failure.kind in TERMINAL_FAILURES) {
                    throw failure
                }

                // Snapshot recovery is also required after a truncated frame or a cursor rejected by the server.
                failures++
                if (failure.kind == FailureKind.THROTTLED || failure.retryAfterMillis != null) {
                    // Do not evade server backpressure by switching immediately to another endpoint.
                    val wait = failure.retryAfterMillis ?: 4_000
                    if (wait > 25_000) throw failure
                    pause(wait)
                } else {
                    pause((400L shl (failures - 1)).coerceAtMost(4_000))
                }

                reconcile(load(), save)
                if (load().terminal) return
                streaming = failure.retryable && failures < streamAttempts
            }
        }
    }

    private suspend fun reconcile(
        current: Run,
        save: suspend (Run) -> Unit,
        minimumSequence: Long = current.sequence,
    ) {
        val latest = backend.run(current.id)
        val matchesIdentity = latest.id == current.id && latest.conversationId == current.conversationId
        if (!matchesIdentity || latest.sequence < minimumSequence) {
            throw BackendFailure(FailureKind.PROTOCOL)
        }
        save(latest)
    }

    private fun applyEvent(current: Run, event: RunEvent): Run {
        val message = event.message
        if (message != null) {
            val invalid = message.runId != current.id ||
                message.conversationId != current.conversationId ||
                message.id != current.output.id ||
                message.role != "assistant" ||
                message.sequence != current.output.sequence
            if (invalid) {
                throw BackendFailure(FailureKind.PROTOCOL)
            }
        }

        val status = when (event.kind) {
            EventKind.ACCEPTED -> "queued"
            EventKind.STARTED -> "running"
            EventKind.CANCEL_REQUESTED -> "cancel_requested"
            EventKind.COMPLETED -> "completed"
            EventKind.FAILED -> "failed"
            EventKind.CANCELLED -> "cancelled"
            else -> current.status
        }

        if (event.kind in MESSAGE_REQUIRED_KINDS && message == null) {
            throw BackendFailure(FailureKind.PROTOCOL)
        }

        return current.copy(
            status = status,
            sequence = event.sequence,
            output = message ?: current.output,
            error = event.error ?: current.error,
        )
    }

    private companion object {
        private val TERMINAL_FAILURES = setOf(
            FailureKind.AUTHENTICATION,
            FailureKind.FORBIDDEN,
            FailureKind.CLOSED,
        )
        private val MESSAGE_REQUIRED_KINDS = setOf(
            EventKind.MESSAGE,
            EventKind.COMPLETED,
            EventKind.FAILED,
            EventKind.CANCELLED,
        )
    }
}
