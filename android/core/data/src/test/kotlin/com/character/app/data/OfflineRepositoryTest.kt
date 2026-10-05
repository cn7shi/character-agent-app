package com.character.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.character.app.domain.*
import com.character.app.network.RemoteFailure
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class FixtureBackend : BackendGateway {
    val character = Character("character", "林舟", "开发角色", 2)
    val conversation = Conversation("conversation", character.id, "林舟", "2026-10-05T00:00:00Z")
    val requests = mutableListOf<List<String>>()
    val conversationKeys = mutableListOf<String>()
    val receipts = mutableMapOf<String, Run>()
    var loseNextResponse = false
    var loseConversationResponse = false
    var forbidden = false
    var offline = false
    var writes = 0
    var current: Run? = null
    fun sample(status: String = "completed", sequence: Long = 5) = Run("run", conversation.id, status, sequence,
        Message("input", conversation.id, "run", 1, "user", listOf(MessageBlock("text", "记笔记：测试")), "completed", "2026-10-05T00:00:00Z"),
        Message("output", conversation.id, "run", 2, "assistant", listOf(MessageBlock("text", "【模拟回复】已保存笔记")), if (status == "running") "streaming" else status, "2026-10-05T00:00:01Z"), true, null)
    override suspend fun characters(cursor: String?): Page<Character> { if (offline) throw RemoteFailure(); return Page(listOf(character), null) }
    override suspend fun conversations(cursor: String?) = Page(listOf(conversation), null)
    override suspend fun messages(conversationId: String, cursor: String?) = Page(current?.let { listOf(it.output, it.input) }.orEmpty(), null)
    override suspend fun notes(cursor: String?) = Page(if (writes > 0) listOf(Note("note", conversation.id, "测试", "- 测试", "2026-10-05T00:00:00Z")) else emptyList(), null)
    override suspend fun createConversation(characterId: String, requestKey: String): Conversation {
        conversationKeys += requestKey
        if (loseConversationResponse) { loseConversationResponse = false; throw RemoteFailure() }
        return conversation
    }
    override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run {
        requests += listOf(conversationId, messageId, requestKey, text)
        if (forbidden) throw RemoteFailure(422)
        val result = receipts.getOrPut(requestKey) { writes++; sample().also { current = it } }
        if (loseNextResponse) { loseNextResponse = false; throw RemoteFailure() }
        return result
    }
    override suspend fun run(runId: String) = checkNotNull(current)
    override suspend fun cancel(runId: String) = sample("cancelled", 9).also { current = it }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineRepositoryTest {
    private lateinit var database: ClientDatabase
    private lateinit var context: Context
    private lateinit var name: String
    private val backend = FixtureBackend()
    private fun repository(scope: String = "profile-a") = OfflineRepository(database, scope, backend)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "test-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, ClientDatabase::class.java, name).build()
    }
    @After fun close() { database.close(); context.deleteDatabase(name) }
    private fun reopen() { database.close(); database = Room.databaseBuilder(context, ClientDatabase::class.java, name).build() }

    @Test fun lostResponseThenRestartRetriesOriginalIdsAndCreatesOneArtifact() = runBlocking {
        backend.loseNextResponse = true
        try { repository().send("conversation", "记笔记：测试"); fail() } catch (_: RemoteFailure) { }
        val pending = repository().pending("conversation").first().single()
        assertFalse(pending.blocked)
        reopen()
        repository().retry(pending.id)
        repository().refreshNotes()
        assertEquals(backend.requests[0], backend.requests[1])
        assertEquals(1, backend.writes)
        assertEquals(2, repository().messages("conversation").first().size)
        assertTrue(repository().pending("conversation").first().isEmpty())
        assertEquals(1, repository().notes().first().size)
    }
    @Test fun cachedDataSurvivesRestartOfflineAndIsIsolatedByProfile() = runBlocking {
        repository().refreshHome()
        repository().send("conversation", "记笔记：测试")
        repository().refreshNotes()
        reopen()
        backend.offline = true
        try { repository().refreshHome(); fail() } catch (_: RemoteFailure) { }
        assertEquals("林舟", repository().characters().first().single().name)
        assertEquals(2, repository().messages("conversation").first().size)
        assertEquals(1, repository().notes().first().size)
        assertTrue(repository("profile-b").characters().first().isEmpty())
        assertTrue(repository("profile-b").messages("conversation").first().isEmpty())
        assertTrue(repository("profile-b").notes().first().isEmpty())
    }
    @Test fun staleSnapshotCannotRegressTerminalStateOrDuplicateMessages() = runBlocking {
        repository().store(backend.sample("completed", 8))
        repository().store(backend.sample("running", 4))
        repository().store(backend.sample("completed", 8))
        assertEquals("completed", repository().runs("conversation").first().single().status)
        assertEquals(2, repository().messages("conversation").first().size)
    }
    @Test fun messageBlocksKeepNarrationSeparateAcrossPersistence() = runBlocking {
        val run = backend.sample()
        val blocks = listOf(MessageBlock("narration", "他递来笔记。"), MessageBlock("text", "已经保存。"))
        repository().store(run.copy(output = run.output.copy(blocks = blocks)))
        reopen()
        assertEquals(blocks, repository().messages("conversation").first().last().blocks)
    }
    @Test fun definitiveRejectionCanBeRemovedButAmbiguousSendCannot() = runBlocking {
        backend.forbidden = true
        try { repository().send("conversation", "invalid"); fail() } catch (_: RemoteFailure) { }
        val rejected = repository().pending("conversation").first().single()
        assertTrue(rejected.blocked)
        repository().dismiss(rejected.id)
        backend.forbidden = false
        backend.loseNextResponse = true
        try { repository().send("conversation", "test"); fail() } catch (_: RemoteFailure) { }
        val unknown = repository().pending("conversation").first().single()
        try { repository().dismiss(unknown.id); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, repository().pending("conversation").first().size)
    }
    @Test fun duplicateSendIsBlockedWhileAcceptanceIsUnknown() = runBlocking {
        backend.loseNextResponse = true
        try { repository().send("conversation", "test"); fail() } catch (_: RemoteFailure) { }
        try { repository().send("conversation", "test"); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, backend.requests.size)
    }
    @Test fun conversationCreationRetryAfterRestartUsesOriginalKey() = runBlocking {
        backend.loseConversationResponse = true
        try { repository().createConversation("character"); fail() } catch (_: RemoteFailure) { }
        reopen()
        repository().createConversation("character")
        assertEquals(backend.conversationKeys[0], backend.conversationKeys[1])
        assertEquals(1, repository().conversations().first().size)
    }
    @Test fun reopeningConversationDiscoversActiveRunAndCancelKeepsPartialText() = runBlocking {
        backend.current = backend.sample("running", 4)
        repository().refreshConversation("conversation")
        assertEquals("running", repository().runs("conversation").first().single().status)
        repository().cancel("run")
        assertEquals("cancelled", repository().runs("conversation").first().single().status)
        assertTrue(repository().messages("conversation").first().last().text.isNotBlank())
    }
}
