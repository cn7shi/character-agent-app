package com.character.app.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

private fun sample(sequence: Long = 1, status: String = "queued") = Run("run", "conversation", status, sequence,
    Message("input", "conversation", "run", 1, "user", listOf(MessageBlock("text", "hello")), "completed", "now"),
    Message("output", "conversation", "run", 2, "assistant", listOf(MessageBlock("text", "partial")), "streaming", "now"), true, null)

private class MemoryAdapter : BackendAdapter {
    override var supportsEvents = true
    var snapshot = sample()
    var reads = 0
    var cancels = 0
    var releases = 0
    val cursors = mutableListOf<Long>()
    var stream: (Long) -> Flow<RunEvent> = { emptyFlow() }
    override fun events(runId: String, afterSequence: Long): Flow<RunEvent> { cursors += afterSequence; return stream(afterSequence) }
    override suspend fun run(runId: String): Run { reads++; return snapshot }
    override suspend fun cancel(runId: String): Run { cancels++; return snapshot }
    override fun close() { releases++ }
    override suspend fun characters(cursor: String?) = Page<Character>(emptyList(), null)
    override suspend fun conversations(cursor: String?) = Page<Conversation>(emptyList(), null)
    override suspend fun messages(conversationId: String, cursor: String?) = Page<Message>(emptyList(), null)
    override suspend fun notes(cursor: String?) = Page<Note>(emptyList(), null)
    override suspend fun createConversation(characterId: String, requestKey: String): Conversation = error("Unexpected write")
    override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run = error("Unexpected write")
}

class RunMonitorTest {
    @Test fun reconnectUsesCommittedCursorAndDropsReplayWithoutDuplicatingText() = runBlocking {
        val backend = MemoryAdapter()
        var local = sample()
        val saved = mutableListOf<Long>()
        backend.stream = { cursor -> flow {
            if (cursor == 1L) {
                backend.snapshot = sample(2, "running")
                emit(RunEvent("run", 2, EventKind.STARTED))
                throw BackendFailure(FailureKind.CONNECTION)
            }
            emit(RunEvent("run", 2, EventKind.STARTED))
            emit(RunEvent("run", 3, EventKind.COMPLETED, local.output.copy(status = "completed")))
            awaitCancellation() // Completion must close even a server that keeps its stream open.
        } }
        withTimeout(1500) { RunMonitor(backend, pause = {}).follow({ local }, { local = it; saved += it.sequence }) }
        assertEquals(listOf(1L, 2L), backend.cursors)
        assertEquals(listOf(2L, 2L, 3L), saved)
        assertEquals("completed", local.status)
        assertEquals("partial", local.output.text)
    }
    @Test fun exhaustedReconnectsFallBackToSnapshotsAndEofNeverMeansSuccess() = runBlocking {
        val backend = MemoryAdapter()
        var local = sample()
        val waits = mutableListOf<Long>()
        withTimeout(1000) { RunMonitor(backend, pause = {
            waits += it
            if (it == 1000L) backend.snapshot = sample(9, "completed")
        }).follow({ local }, { local = it }) }
        assertEquals(3, backend.cursors.size)
        assertEquals(listOf(400L, 800L, 1600L, 1000L), waits)
        assertEquals(4, backend.reads)
        assertEquals(9, local.sequence)
    }
    @Test fun missingSequenceAndUnknownEventReconcileFullSnapshot() = runBlocking {
        for (event in listOf(RunEvent("run", 4, EventKind.MESSAGE, sample().output), RunEvent("run", 2, EventKind.UNKNOWN))) {
            val backend = MemoryAdapter().apply { snapshot = sample(5, "completed"); stream = { flowOf(event) } }
            var local = sample()
            RunMonitor(backend, pause = {}).follow({ local }, { local = it })
            assertEquals(5, local.sequence)
            assertEquals(1, backend.reads)
        }
    }
    @Test fun malformedEventFallsBackWithoutPersistingForeignMessage() = runBlocking {
        val backend = MemoryAdapter().apply {
            snapshot = sample(5, "completed")
            stream = { flowOf(RunEvent("run", 2, EventKind.MESSAGE, sample().output.copy(conversationId = "other"))) }
        }
        var local = sample()
        val saved = mutableListOf<Run>()
        RunMonitor(backend, pause = {}).follow({ local }, { local = it; saved += it })
        assertTrue(saved.all { it.output.conversationId == "conversation" })
        assertEquals(1, saved.size)
    }
    @Test fun authenticationFailureStopsWithoutReconnectOrSnapshotRequest() = runBlocking {
        val backend = MemoryAdapter().apply { stream = { flow { throw BackendFailure(FailureKind.AUTHENTICATION) } } }
        try { RunMonitor(backend, pause = { fail("No backoff") }).follow({ sample() }, { fail("No save") }); fail() }
        catch (error: BackendFailure) { assertEquals(FailureKind.AUTHENTICATION, error.kind) }
        assertEquals(1, backend.cursors.size)
        assertEquals(0, backend.reads)
    }
    @Test fun cancellationClosesObservationWithoutSendingServerCancel() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        var closed = false
        val backend = MemoryAdapter().apply { stream = { flow { try { opened.complete(Unit); awaitCancellation() } finally { closed = true } } } }
        val job = launch { RunMonitor(backend).follow({ sample() }, {}) }
        opened.await()
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(closed)
        assertEquals(0, backend.cancels)
    }
    @Test fun pollOnlyAdapterUsesSameSnapshotRules() = runBlocking {
        val backend = MemoryAdapter().apply { supportsEvents = false; snapshot = sample(7, "cancelled") }
        var local = sample()
        RunMonitor(backend, pause = {}).follow({ local }, { local = it })
        assertEquals("cancelled", local.status)
        assertEquals(0, backend.cursors.size)
        assertEquals(1, backend.reads)
    }
    @Test fun serverBackpressureIsRespectedBeforeSnapshotFallback() = runBlocking {
        val backend = MemoryAdapter().apply {
            snapshot = sample(9, "completed")
            stream = { flow { throw BackendFailure(FailureKind.THROTTLED, 2000) } }
        }
        var local = sample()
        val waits = mutableListOf<Long>()
        RunMonitor(backend, pause = { waits += it; assertEquals(0, backend.reads) }).follow({ local }, { local = it })
        assertEquals(listOf(2000L), waits)
    }
}
