package com.character.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.character.app.data.*
import com.character.app.domain.*
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class TestConnections : ConnectionStore {
    override var mode = ConnectionMode.REALTIME
    override var dark = true
    private var connection: Pair<String, String>? = "http://127.0.0.1:8000/" to UUID.randomUUID().toString()
    override fun save(url: String, credential: String) { connection = url to credential }
    override fun read() = connection
    override fun clear() { connection = null }
}

private class DelayedBackend : BackendAdapter {
    val character = Character("character", "测试角色", "", 1)
    val conversation = Conversation("conversation", character.id, "测试会话", "now")
    val submitted = CompletableDeferred<Unit>()
    val reply = CompletableDeferred<Unit>()
    var beforeNotes: suspend () -> Unit = {}
    var current: Run? = null
    override val supportsEvents = false
    override fun events(runId: String, afterSequence: Long) = emptyFlow<RunEvent>()
    override fun close() = Unit
    override suspend fun characters(cursor: String?) = Page(listOf(character), null)
    override suspend fun conversations(cursor: String?) = Page(listOf(conversation), null)
    override suspend fun notes(cursor: String?): Page<Note> { beforeNotes(); return Page(emptyList(), null) }
    override suspend fun messages(conversationId: String, cursor: String?) = Page(current?.let { listOf(it.input, it.output) }.orEmpty(), null)
    override suspend fun createConversation(characterId: String, requestKey: String) = conversation
    override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run {
        submitted.complete(Unit)
        reply.await()
        return Run("run", conversationId, "completed", 3,
            Message(messageId, conversationId, "run", 1, "user", listOf(MessageBlock("text", text)), "completed", "now"),
            Message("output", conversationId, "run", 2, "assistant", listOf(MessageBlock("text", "回复")), "completed", "now"), true, null).also { current = it }
    }
    override suspend fun run(runId: String) = checkNotNull(current)
    override suspend fun cancel(runId: String) = checkNotNull(current)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClientViewModelTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var store: ViewModelStore
    private lateinit var model: ClientViewModel
    private lateinit var database: ClientDatabase
    private val backend = DelayedBackend()

    @Before fun setup() {
        // Resolve Android/Room classes on Robolectric's test thread before starting the UI dispatcher.
        val application = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(application, ClientDatabase::class.java).build()
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking(main) {
            model = ClientViewModel(application, TestConnections(), database, { _, _ -> backend }, main)
            store = ViewModelStore().also { it.put("model", model) }
            ready()
        }
    }
    @After fun close() {
        try { if (::store.isInitialized) runBlocking(main) {
            store.clear()
            withTimeout(5000) { while (database.isOpen) delay(10) }
        } }
        finally { if (::main.isInitialized) main.close() }
    }
    private suspend fun ready() = try { withTimeout(10_000) { model.state.first { !it.busy && it.characters.isNotEmpty() } } }
    catch (error: TimeoutCancellationException) {
        val state = model.state.value
        throw AssertionError("ViewModel did not settle: connected=${state.connected}, busy=${state.busy}, roles=${state.characters.size}, error=${state.error}", error)
    }

    @Test fun modeSwitchKeepsDraftInSameProfile() = runBlocking(main) {
        model.open(backend.conversation)
        model.draft("还没有发送的内容")
        model.back()
        model.mode(true)
        ready()
        model.open(backend.conversation)
        assertEquals("还没有发送的内容", model.state.value.draft)
    }

    @Test fun delayedSendDoesNotEraseNewTyping() = runBlocking(main) {
        model.open(backend.conversation)
        model.draft("第一条消息")
        model.send()
        withTimeout(5000) { backend.submitted.await() }
        model.draft("下一条还在输入")
        backend.reply.complete(Unit)
        ready()
        assertEquals("下一条还在输入", model.state.value.draft)
        assertEquals("第一条消息", backend.current?.input?.text)
    }

    @Test fun newProfileDoesNotReusePreviousDraft() = runBlocking(main) {
        model.open(backend.conversation)
        model.draft("旧连接的内容")
        model.back()
        model.connect("http://127.0.0.1:8000/", UUID.randomUUID().toString())
        ready()
        model.open(backend.conversation)
        assertEquals("", model.state.value.draft)
    }

    @Test fun retypingIdenticalTextIsStillANewDraft() = runBlocking(main) {
        model.open(backend.conversation)
        model.draft("相同内容")
        model.send()
        withTimeout(5000) { backend.submitted.await() }
        model.draft("")
        model.draft("相同内容")
        backend.reply.complete(Unit)
        ready()
        assertEquals("相同内容", model.state.value.draft)
    }

    @Test fun acknowledgedUneditedDraftIsCleared() = runBlocking(main) {
        model.open(backend.conversation)
        model.draft("正常发送")
        backend.reply.complete(Unit)
        model.send()
        ready()
        assertEquals("", model.state.value.draft)
        assertEquals("正常发送", backend.current?.input?.text)
    }

    @Test fun activeRunRejectionDoesNotMistakeHistoryForAcknowledgement() = runBlocking(main) {
        backend.reply.complete(Unit)
        backend.current = backend.createRun(backend.conversation.id, "old-input", "old-request", "重复内容").let {
            it.copy(status = "running", output = it.output.copy(status = "streaming"))
        }
        model.open(backend.conversation)
        withTimeout(5000) { model.state.first { it.runs.any { run -> !run.terminal } } }
        model.draft("重复内容")
        model.send()
        ready()
        assertNotNull(model.state.value.error)
        assertEquals("重复内容", model.state.value.draft)
    }

    @Test fun clearingWaitsForSessionCleanupBeforeClosingDatabase() = runBlocking(main) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backend.beforeNotes = {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { release.await() } }
        }
        model.refreshNotes()
        withTimeout(5000) { started.await() }
        try {
            store.clear()
            assertTrue("Database must outlive cancelled session work", database.isOpen)
        } finally { release.complete(Unit) }
        withTimeout(5000) { while (database.isOpen) delay(10) }
    }
}
