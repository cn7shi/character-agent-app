package com.character.app.domain

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class FailureKind { CONNECTION, TIMEOUT, THROTTLED, UNAVAILABLE, AUTHENTICATION, FORBIDDEN, NOT_FOUND, CONFLICT, INVALID_REQUEST, PROTOCOL, UNSUPPORTED, CLOSED }

/** Safe metadata only: adapters must never attach raw response bodies, URLs or credentials. */
open class BackendFailure(val kind: FailureKind, val retryAfterMillis: Long? = null,
                          val definitiveRejection: Boolean = false) : IOException("Backend operation failed: $kind") {
    val retryable get() = kind in setOf(FailureKind.CONNECTION, FailureKind.TIMEOUT, FailureKind.THROTTLED, FailureKind.UNAVAILABLE)
}

class RetryPolicy(
    private val attempts: Int = 3,
    private val budgetMillis: Long = 25_000,
    private val baseDelayMillis: Long = 400,
    private val maxDelayMillis: Long = 4_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { Random.nextLong(it / 2, it + 1) },
) {
    init { require(attempts in 1..10 && budgetMillis > 0 && baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis) }

    /** Call only for reads or operations with an explicit server-side idempotency guarantee. */
    suspend fun <T> execute(preserveAmbiguousWrite: Boolean = false, operation: suspend () -> T): T {
        // The deadline includes in-flight attempts, not just sleeps. Parent cancellation is propagated.
        val result = withTimeoutOrNull(budgetMillis) {
            var waits = 0L
            var ambiguous = false
            for (attempt in 0 until attempts) {
                try { return@withTimeoutOrNull Box(operation()) }
                catch (failure: BackendFailure) {
                    ambiguous = ambiguous || (preserveAmbiguousWrite && !failure.definitiveRejection)
                    val finalFailure = if (ambiguous && failure.definitiveRejection)
                        BackendFailure(failure.kind, failure.retryAfterMillis) else failure
                    if (!failure.retryable || attempt == attempts - 1) throw finalFailure
                    val backoff = jitter((baseDelayMillis * (1L shl attempt)).coerceAtMost(maxDelayMillis)).coerceAtLeast(0)
                    val wait = maxOf(backoff, failure.retryAfterMillis ?: 0)
                    // Never shorten Retry-After to fit our budget; leave the original operation pending.
                    if (wait >= budgetMillis - waits) throw finalFailure
                    waits += wait
                    pause(wait)
                }
            }
            error("Unreachable")
        }
        if (result == null) throw BackendFailure(FailureKind.TIMEOUT)
        return result.value
    }
    private data class Box<T>(val value: T)
}

/** Only these declared idempotent business operations receive retries; transport POSTs do not. */
class ReliableBackend(private val delegate: BackendAdapter, private val policy: RetryPolicy = RetryPolicy()) : BackendAdapter {
    override val supportsEvents get() = delegate.supportsEvents
    override fun events(runId: String, afterSequence: Long) = delegate.events(runId, afterSequence)
    override fun close() = delegate.close()
    override suspend fun characters(cursor: String?) = policy.execute { delegate.characters(cursor) }
    override suspend fun conversations(cursor: String?) = policy.execute { delegate.conversations(cursor) }
    override suspend fun messages(conversationId: String, cursor: String?) = policy.execute { delegate.messages(conversationId, cursor) }
    override suspend fun notes(cursor: String?) = policy.execute { delegate.notes(cursor) }
    override suspend fun createConversation(characterId: String, requestKey: String): Conversation {
        require(requestKey.isNotBlank())
        return policy.execute(preserveAmbiguousWrite = true) { delegate.createConversation(characterId, requestKey) }
    }
    override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run {
        require(requestKey.isNotBlank() && messageId.isNotBlank())
        return policy.execute(preserveAmbiguousWrite = true) { delegate.createRun(conversationId, messageId, requestKey, text) }
    }
    override suspend fun run(runId: String) = policy.execute { delegate.run(runId) }
    // API v1 cancel returns the existing task and never starts another execution.
    override suspend fun cancel(runId: String) = policy.execute { delegate.cancel(runId) }
}
