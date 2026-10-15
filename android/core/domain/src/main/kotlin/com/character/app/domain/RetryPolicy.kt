package com.character.app.domain

import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Configurable exponential backoff retry executor with budget deadline and ambiguous-write tracking.
 *
 * Call only for reads or operations with an explicit server-side idempotency guarantee.
 */
class RetryPolicy(
    private val attempts: Int = 3,
    private val budgetMillis: Long = 25_000,
    private val baseDelayMillis: Long = 400,
    private val maxDelayMillis: Long = 4_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { Random.nextLong(it / 2, it + 1) },
) {
    init {
        require(attempts in 1..10 && budgetMillis > 0 && baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis) {
            "Invalid retry policy bounds: attempts=$attempts, budgetMillis=$budgetMillis, baseDelay=$baseDelayMillis, maxDelay=$maxDelayMillis"
        }
    }

    /**
     * Executes the [operation] within the retry policy budget.
     *
     * @param preserveAmbiguousWrite If true, an ambiguous intermediate failure (like a connection drop during a write)
     *        is preserved even if a subsequent attempt is definitively rejected.
     */
    suspend fun <T> execute(
        preserveAmbiguousWrite: Boolean = false,
        operation: suspend () -> T,
    ): T {
        // The deadline includes in-flight attempts, not just sleeps. Parent cancellation is propagated.
        val result = withTimeoutOrNull(budgetMillis) {
            var waits = 0L
            var ambiguous = false

            for (attempt in 0 until attempts) {
                try {
                    return@withTimeoutOrNull ExecutionResult(operation())
                } catch (failure: BackendFailure) {
                    ambiguous = ambiguous || (preserveAmbiguousWrite && !failure.definitiveRejection)
                    val finalFailure = if (ambiguous && failure.definitiveRejection) {
                        BackendFailure(failure.kind, failure.retryAfterMillis)
                    } else {
                        failure
                    }

                    if (!failure.retryable || attempt == attempts - 1) {
                        throw finalFailure
                    }

                    val backoff = jitter(
                        (baseDelayMillis * (1L shl attempt)).coerceAtMost(maxDelayMillis)
                    ).coerceAtLeast(0)
                    val wait = maxOf(backoff, failure.retryAfterMillis ?: 0)

                    // Never shorten Retry-After to fit our budget; leave the original operation pending.
                    if (wait >= budgetMillis - waits) {
                        throw finalFailure
                    }

                    waits += wait
                    pause(wait)
                }
            }
            error("Unreachable")
        }

        if (result == null) {
            throw BackendFailure(FailureKind.TIMEOUT)
        }
        return result.value
    }

    private data class ExecutionResult<T>(val value: T)
}
