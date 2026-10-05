package com.character.app.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    @Test fun laterRejectionDoesNotDisproveAcceptanceOfAnEarlierAttempt() = runBlocking {
        var calls = 0
        try {
            RetryPolicy(pause = {}).execute(preserveAmbiguousWrite = true) {
                calls++
                if (calls == 1) throw BackendFailure(FailureKind.CONNECTION)
                throw BackendFailure(FailureKind.AUTHENTICATION, definitiveRejection = true)
            }
            fail()
        } catch (error: BackendFailure) {
            assertEquals(FailureKind.AUTHENTICATION, error.kind)
            assertFalse(error.definitiveRejection)
        }
        assertEquals(2, calls)
    }
    @Test fun transientFailuresAreBoundedWithBackoffAndRetryAfter() = runBlocking {
        val waits = mutableListOf<Long>()
        var calls = 0
        val policy = RetryPolicy(pause = { waits += it }, jitter = { it })
        val value = policy.execute {
            calls++
            if (calls < 3) throw BackendFailure(FailureKind.THROTTLED, 900)
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, calls)
        assertEquals(listOf(900L, 900L), waits)
    }
    @Test fun permanentFailuresNeverRetryAndLongServerDelayIsNotShortened() = runBlocking {
        for (failure in listOf(BackendFailure(FailureKind.AUTHENTICATION), BackendFailure(FailureKind.PROTOCOL), BackendFailure(FailureKind.THROTTLED, 60_000))) {
            var calls = 0
            var waits = 0
            try { RetryPolicy(pause = { waits++ }).execute { calls++; throw failure }; fail() }
            catch (actual: BackendFailure) { assertSame(failure, actual) }
            assertEquals(1, calls)
            assertEquals(0, waits)
        }
    }
    @Test fun persistentFailureStopsAtAttemptLimit() = runBlocking {
        var calls = 0
        try { RetryPolicy(pause = {}).execute { calls++; throw BackendFailure(FailureKind.CONNECTION) }; fail() }
        catch (_: BackendFailure) { }
        assertEquals(3, calls)
    }
    @Test fun deadlineIncludesHangingAttemptAndCancellationStopsBackoff() = runBlocking {
        var calls = 0
        try { RetryPolicy(budgetMillis = 80).execute { calls++; awaitCancellation() }; fail() }
        catch (error: BackendFailure) { assertEquals(FailureKind.TIMEOUT, error.kind) }
        assertEquals(1, calls)
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            RetryPolicy(pause = { waiting.complete(Unit); awaitCancellation() }).execute {
                calls++; throw BackendFailure(FailureKind.CONNECTION)
            }
        }
        waiting.await()
        withTimeout(1000) { job.cancelAndJoin() }
        assertEquals(2, calls)
    }
    @Test fun callerCancellationAndProgrammingErrorsAreNotNetworkFailures() = runBlocking {
        var calls = 0
        val error = IllegalStateException("local invariant")
        try { RetryPolicy().execute { calls++; throw error }; fail() }
        // Coroutine stack recovery can copy standard exceptions; type/message are the contract.
        catch (actual: IllegalStateException) { assertEquals(error.message, actual.message) }
        assertEquals(1, calls)
        assertNull(RetryPolicy().execute<String?> { null })
        try { withTimeout(50) { RetryPolicy().execute { awaitCancellation() } }; fail() }
        catch (_: TimeoutCancellationException) { }
    }
}
