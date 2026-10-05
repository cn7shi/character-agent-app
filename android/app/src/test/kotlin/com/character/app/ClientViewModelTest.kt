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
    var offline = false
    var current: Run? = null
    override val supportsEvents = false
    override fun events(runId: String, afterSequence: Long) = emptyFlow<RunEvent>()
    override fun close() = Unit
    override suspend fun characters(cursor: String?): Page<Character> {
        if (offline) throw BackendFailure(FailureKind.CONNECTION)
        return Page(listOf(character), null)
    }
    override suspend fun conversations(cursor: String?) = Page(listOf(conversation), null)
    override suspend fun notes(cursor: String?): Page<Note> { beforeNotes(); return Page(emptyList(), null) }
    override suspend fun messages(conversationId: String, cursor: String?): Page<Message> {
        if (offline) throw BackendFailure(FailureKind.CONNECTION)
        return Page(current?.let { listOf(it.input, it.output) }.orEmpty(), null)
    }
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
    private lateinit var application: Application
    private val databaseName = "viewmodel-${UUID.randomUUID()}.db"
    private val connections = TestConnections()
    private val backend = DelayedBackend()

    @Before fun setup() {
        // Resolve Android/Room classes on Robolectric's test thread before starting the UI dispatcher.
        application = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(application, ClientDatabase::class.java, databaseName).build()
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking(main) {
            model = ClientViewModel(application, connections, database, { _, _ -> backend }, main)
            store = ViewModelStore().also { it.put("model", model) }
            ready()
        }
    }
    @After fun close() {
        try { if (::store.isInitialized) runBlocking(main) {
            store.clear()
            withTimeout(5000) { while (database.isOpen) delay(10) }
        } }
        finally {
            if (::main.isInitialized) main.close()
            if (::application.isInitialized) application.deleteDatabase(databaseName)
        }
    }
    private suspend fun ready() = try { withTimeout(10_000) { model.state.first { !it.busy && it.characters.isNotEmpty() } } }
    catch (error: TimeoutCancellationException) {
        val state = model.state.value
        throw AssertionError("ViewModel did not settle: connected=${state.connected}, busy=${state.busy}, roles=${state.characters.size}, error=${state.error}", error)
    }

    private suspend fun open(conversation: Conversation = backend.conversation) {
        model.open(conversation)
        withTimeout(5000) { model.state.first { it.selected?.id == conversation.id && it.draftState != DraftSaveState.LOADING } }
    }

    private suspend fun saved() { withTimeout(5000) { model.state.first { it.draftState == DraftSaveState.SAVED } } }

    private suspend fun recreate() {
        store.clear()
        withTimeout(5000) { while (database.isOpen) delay(10) }
        database = Room.databaseBuilder(application, ClientDatabase::class.java, databaseName).build()
        model = ClientViewModel(application, connections, database, { _, _ -> backend }, main)
        store = ViewModelStore().also { it.put("model", model) }
        ready()
    }

    @Test fun modeSwitchKeepsDraftInSameProfile() = runBlocking(main) {
        open()
        model.draft("还没有发送的内容")
        model.back()
        model.mode(true)
        ready()
        open()
        assertEquals("还没有发送的内容", model.state.value.draft)
    }

    @Test fun delayedSendDoesNotEraseNewTyping() = runBlocking(main) {
        open()
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
        open()
        model.draft("旧连接的内容")
        model.back()
        model.connect("http://127.0.0.1:8000/", UUID.randomUUID().toString())
        ready()
        open()
        assertEquals("", model.state.value.draft)
    }

    @Test fun retypingIdenticalTextIsStillANewDraft() = runBlocking(main) {
        open()
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
        open()
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
        open()
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

    @Test fun draftsRestoreOfflineAcrossConversations() = runBlocking(main) {
        open()
        val original = "  第一行\n第二行 🌙  "
        model.draft(original)
        saved()
        val other = backend.conversation.copy(id = "other")
        open(other)
        model.draft("另一个会话")
        saved()
        backend.offline = true
        recreate()
        open()
        assertEquals(original, model.state.value.draft)
        open(other)
        assertEquals("另一个会话", model.state.value.draft)
    }

    @Test fun nextDraftSurvivesDisposalDuringSend() = runBlocking(main) {
        open()
        model.draft("已提交的内容")
        model.send()
        withTimeout(5000) { backend.submitted.await() }
        repeat(100) { model.draft("下一条 $it") }
        // No wait for saving: ordinary disposal must drain queued edits while cancelling the HTTP work.
        recreate()
        open()
        assertEquals("下一条 99", model.state.value.draft)
        withTimeout(5000) { model.state.first { it.pending.isNotEmpty() } }
        assertEquals("已提交的内容", model.state.value.pending.single().text)
    }

    @Test fun clearingInputPersistsAcrossRecreation() = runBlocking(main) {
        open()
        model.draft("准备删掉")
        saved()
        model.draft("")
        recreate()
        open()
        assertEquals("", model.state.value.draft)
    }

    @Test fun submittedDraftDoesNotReappearAfterRecreation() = runBlocking(main) {
        open()
        model.draft("只发送一次")
        backend.reply.complete(Unit)
        model.send()
        ready()
        recreate()
        open()
        assertEquals("", model.state.value.draft)
        assertEquals("只发送一次", backend.current?.input?.text)
    }

    @Test fun returningToProfileRestoresItsOwnDraft() = runBlocking(main) {
        val original = connections.read()!!
        open()
        model.draft("身份 A")
        model.connect(original.first, UUID.randomUUID().toString())
        ready()
        open()
        assertEquals("", model.state.value.draft)
        model.draft("身份 B")
        model.connect(original.first, original.second)
        ready()
        open()
        assertEquals("身份 A", model.state.value.draft)
        recreate()
        open()
        assertEquals("身份 A", model.state.value.draft)
    }

    @Test fun storageFailureCanBeRetriedWithoutSending() = runBlocking(main) {
        open()
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_save BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT, 'test save failure'); END")
        }
        model.draft("必须先保存")
        withTimeout(5000) { model.state.first { it.draftState == DraftSaveState.FAILED } }
        model.send()
        ready()
        assertFalse(backend.submitted.isCompleted)
        assertEquals("必须先保存", model.state.value.draft)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_save") }
        model.retryDraftSave()
        saved()
        recreate()
        open()
        assertEquals("必须先保存", model.state.value.draft)
    }

    @Test fun switchingModeDuringSendDoesNotRestoreConsumedDraft() = runBlocking(main) {
        open()
        model.draft("已经交给待发送区")
        model.send()
        withTimeout(5000) { backend.submitted.await() }
        model.back()
        model.mode(true)
        ready()
        open()
        assertEquals("", model.state.value.draft)
        withTimeout(5000) { model.state.first { it.pending.isNotEmpty() } }
        assertEquals("已经交给待发送区", model.state.value.pending.single().text)
    }

    @Test fun failedSaveAfterReconnectStillOffersRetry() = runBlocking(main) {
        val original = connections.read()!!
        open()
        model.draft("最近保存的内容")
        saved()
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON drafts BEGIN SELECT RAISE(ABORT, 'test update failure'); END")
        }
        model.draft("尚未保存的修改")
        withTimeout(5000) { model.state.first { it.draftState == DraftSaveState.FAILED } }
        model.disconnect()
        model.connect(original.first, original.second)
        ready()
        open()
        assertEquals("最近保存的内容", model.state.value.draft)
        assertEquals(DraftSaveState.FAILED, model.state.value.draftState)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_update") }
        model.retryDraftSave()
        saved()
    }
}
