package com.character.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.character.app.domain.*
import com.character.app.network.RemoteFailure
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class FixtureBackend : BackendAdapter {
    override var supportsEvents = false
    var eventFlow: (Long) -> Flow<RunEvent> = { emptyFlow() }
    val cursors = mutableListOf<Long>()
    override fun events(runId: String, afterSequence: Long): Flow<RunEvent> { cursors += afterSequence; return eventFlow(afterSequence) }
    override fun close() = Unit
    val character = Character("character", "林舟", "开发角色", 2)
    val conversation = Conversation("conversation", character.id, "林舟", "2026-10-05T00:00:00Z")
    val requests = mutableListOf<List<String>>()
    val conversationKeys = mutableListOf<String>()
    val receipts = mutableMapOf<String, Run>()
    private val fingerprints = mutableMapOf<String, List<String>>()
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
        val fingerprint = listOf(conversationId, messageId, text)
        if (fingerprints[requestKey]?.let { it != fingerprint } == true) throw RemoteFailure(409)
        fingerprints[requestKey] = fingerprint
        val result = receipts.getOrPut(requestKey) { writes++; sample().let { it.copy(input = it.input.copy(id = messageId, blocks = listOf(MessageBlock("text", text)))) }.also { current = it } }
        if (loseNextResponse) { loseNextResponse = false; throw RemoteFailure() }
        return result
    }
    override suspend fun run(runId: String) = checkNotNull(current)
    override suspend fun cancel(runId: String) = current?.takeIf { it.terminal } ?: sample("cancelled", 9).also { current = it }
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

    @Test fun profileSaveAndResetSurviveLostResponsesReopenAndIdentitySwitch() = runBlocking {
        var saved = UserProfile()
        var lose = true
        val remote = object : BackendAdapter by backend {
            override suspend fun profile() = saved
            override suspend fun saveProfile(profile: UserProfile): UserProfile {
                saved = profile.copy(version = profile.version + 1)
                if (lose) { lose = false; throw RemoteFailure() }
                return saved
            }
            override suspend fun resetProfile(version: Int) = saveProfile(UserProfile(version = version))
        }
        var repo = OfflineRepository(database, "a", remote)
        val value = UserProfile("旅行者 ☕", "第一行\n第二行")
        repo.refreshProfile()
        try { repo.saveProfile(value); fail() } catch (_: BackendFailure) { }
        reopen()
        repo = OfflineRepository(database, "a", remote)
        assertEquals(ProfileChange(value), repo.pendingProfile().first())
        assertNull(OfflineRepository(database, "b", remote).pendingProfile().first())
        assertNull(OfflineRepository(database, "b", remote).profile().first())
        try { repo.saveProfile(value.copy(nickname = "不可覆盖")); fail() } catch (_: IllegalStateException) { }
        repo.retryProfile()
        assertEquals(value.copy(version = 1), repo.profile().first())
        assertNull(repo.pendingProfile().first())
        lose = true
        try { repo.resetProfile(1); fail() } catch (_: BackendFailure) { }
        reopen()
        repo = OfflineRepository(database, "a", remote)
        assertEquals(ProfileChange(UserProfile(version = 1), true), repo.pendingProfile().first())
        repo.retryProfile()
        assertEquals(UserProfile(version = 2), repo.profile().first())
        assertNull(repo.pendingProfile().first())
    }

    @Test fun profileConflictRequiresExplicitReloadAndFailedReloadRetainsInput() = runBlocking {
        var offline = false
        val latest = UserProfile("其他设备修改", "", 2)
        val remote = object : BackendAdapter by backend {
            override suspend fun profile(): UserProfile { if (offline) throw RemoteFailure(); return latest }
            override suspend fun saveProfile(profile: UserProfile): UserProfile = throw RemoteFailure(409)
        }
        val repo = OfflineRepository(database, "a", remote)
        val input = UserProfile("我的修改", "", 1)
        try { repo.saveProfile(input); fail() } catch (_: BackendFailure) { }
        repo.refreshProfile()
        assertEquals(ProfileChange(input), repo.pendingProfile().first())
        offline = true
        try { repo.reloadProfile(); fail() } catch (_: BackendFailure) { }
        assertEquals(latest, repo.profile().first())
        assertEquals(ProfileChange(input), repo.pendingProfile().first())
        offline = false
        assertEquals(latest, repo.reloadProfile())
        assertNull(repo.pendingProfile().first())
    }

    @Test fun staleProfileResponsesCannotRegressTheCacheOrAcknowledgeBadWrites() = runBlocking {
        var response = UserProfile("新资料", "", 4)
        val remote = object : BackendAdapter by backend {
            override suspend fun profile() = response
            override suspend fun saveProfile(profile: UserProfile) = response
        }
        val repo = OfflineRepository(database, "a", remote)
        repo.refreshProfile()
        response = UserProfile("旧资料", "", 2)
        repo.refreshProfile()
        assertEquals(4, repo.profile().first()?.version)
        response = UserProfile("不一致资料", "", 4)
        try { repo.refreshProfile(); fail() } catch (e: BackendFailure) { assertEquals(FailureKind.PROTOCOL, e.kind) }
        try { repo.saveProfile(UserProfile("我的修改", "", 4)); fail() } catch (e: BackendFailure) { assertEquals(FailureKind.PROTOCOL, e.kind) }
        assertEquals("新资料", repo.profile().first()?.nickname)
        assertNotNull(repo.pendingProfile().first())
    }

    @Test fun characterWriteSurvivesLostResponseAndDatabaseReopen() = runBlocking {
        var accepted: CharacterDraft? = null
        var lose = true
        val calls = mutableListOf<CharacterDraft>()
        val remote = object : BackendAdapter by backend {
            override suspend fun saveCharacter(draft: CharacterDraft): CharacterDraft {
                calls += draft
                if (accepted == null) accepted = draft.copy(version = 1)
                if (lose) { lose = false; throw RemoteFailure() }
                return checkNotNull(accepted)
            }
        }
        val draft = CharacterDraft(UUID.randomUUID().toString(), "角色", "简介", "人设", true, 0)
        try { OfflineRepository(database, "a", remote).saveCharacter(draft); fail() } catch (_: BackendFailure) { }
        reopen()
        val repo = OfflineRepository(database, "a", remote)
        assertEquals(draft, repo.pendingCharacter().first())
        assertNull(OfflineRepository(database, "b", remote).pendingCharacter().first())
        try { repo.saveCharacter(draft.copy(name = "不能替换待确认内容")); fail() } catch (_: IllegalStateException) { }
        repo.saveCharacter(checkNotNull(repo.pendingCharacter().first()))
        assertEquals(listOf(draft, draft), calls)
        assertNull(repo.pendingCharacter().first())
        assertEquals(draft.id, repo.characters().first().single().id)
        assertTrue(repo.characters().first().single().editable)
    }

    @Test fun completeCharacterRefreshRemovesDeletedRowsButFailedPageKeepsCache() = runBlocking {
        var stage = 0
        val remote = object : BackendAdapter by backend {
            override suspend fun characters(cursor: String?): Page<Character> = when (stage) {
                0 -> Page(listOf(backend.character), null)
                1 -> if (cursor == null) Page(emptyList(), "next") else throw RemoteFailure()
                else -> Page(emptyList(), null)
            }
        }
        val repo = OfflineRepository(database, "a", remote)
        repo.refreshHome()
        stage = 1
        try { repo.refreshHome(); fail() } catch (_: BackendFailure) { }
        assertEquals(listOf(backend.character), repo.characters().first())
        stage = 2
        repo.refreshHome()
        assertTrue(repo.characters().first().isEmpty())
        assertEquals(backend.conversation, repo.conversations().first().single())
    }

    @Test fun conflictingCharacterWriteRequiresExplicitReload() = runBlocking {
        // Includes an unconfirmed creation whose version 1 was already edited elsewhere.
        for (version in listOf(0, 1)) {
        val draft = CharacterDraft("role", "旧版", "", "人设", false, version)
        val latest = draft.copy(name = "另一设备的新版本", version = 3)
        val remote = object : BackendAdapter by backend {
            override suspend fun saveCharacter(draft: CharacterDraft): CharacterDraft = throw RemoteFailure(409)
            override suspend fun characterDefinition(id: String) = latest
        }
        val repo = OfflineRepository(database, "a", remote)
        try { repo.saveCharacter(draft); fail() } catch (_: BackendFailure) { }
        assertEquals(draft, repo.pendingCharacter().first())
        assertEquals(latest, repo.reloadCharacter(draft.id))
        assertNull(repo.pendingCharacter().first())
        assertEquals(latest.name, repo.characters().first().single().name)
        }
    }

    @Test fun draftsSurviveReopenAndStayScoped() = runBlocking {
        val first = MessageDraft("first", "  第一行\n第二行 🌙  ")
        val second = MessageDraft("second", "另一会话")
        repository().saveDraft("conversation", first)
        repository().saveDraft("other", second)
        repository("profile-b").saveDraft("conversation", MessageDraft("third", "另一身份"))
        reopen()
        backend.offline = true
        assertEquals(first, repository().draft("conversation"))
        assertEquals(second, repository().draft("other"))
        assertEquals("另一身份", repository("profile-b").draft("conversation")?.text)
        repository().saveDraft("conversation", MessageDraft("empty", ""))
        reopen()
        assertNull(repository().draft("conversation"))
        assertEquals(second, repository().draft("other"))
    }

    @Test fun draftHandoffKeepsOriginalOutboxAfterLostResponse() = runBlocking {
        val draft = MessageDraft("draft", "hello")
        repository().saveDraft("conversation", draft)
        backend.loseNextResponse = true
        try { repository().send("conversation", draft.text, draft.revision); fail() } catch (_: RemoteFailure) { }
        reopen()
        assertNull(repository().draft("conversation"))
        val pending = repository().pending("conversation").first().single()
        repository().retry(pending.id)
        assertEquals(backend.requests[0], backend.requests[1])
        assertEquals(1, backend.writes)
        assertNull(repository().draft("conversation"))
    }

    @Test fun olderSendCannotConsumeNewDraftWithIdenticalText() = runBlocking {
        val latest = MessageDraft("new-edit", "hello")
        repository().saveDraft("conversation", latest)
        repository().send("conversation", "hello", "old-edit")
        reopen()
        assertEquals(latest, repository().draft("conversation"))
    }

    @Test fun invalidSubmissionKeepsDraft() = runBlocking {
        val draft = MessageDraft("revision", "  \n  ")
        repository().saveDraft("conversation", draft)
        try { repository().send("conversation", draft.text, draft.revision); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(draft, repository().draft("conversation"))
        assertTrue(repository().pending("conversation").first().isEmpty())
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun failedHandoffRollsBackOutboxAndRetainsDraft() = runBlocking {
        val draft = MessageDraft("revision", "hello")
        repository().saveDraft("conversation", draft)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_handoff BEFORE DELETE ON drafts BEGIN SELECT RAISE(ABORT, 'test handoff failure'); END")
        try { repository().send("conversation", draft.text, draft.revision); fail() } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(draft, repository().draft("conversation"))
        assertTrue(repository().pending("conversation").first().isEmpty())
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun localCancelClosesStalledStreamAndAllowsNextRun() = runBlocking {
        backend.supportsEvents = true
        backend.current = backend.sample("running", 4)
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val nextOpened = CompletableDeferred<Unit>()
        backend.eventFlow = { flow {
            if (backend.cursors.size == 1) {
                try { opened.complete(Unit); awaitCancellation() }
                finally { closed.complete(Unit) }
            } else { nextOpened.complete(Unit); awaitCancellation() }
        } }
        val repo = repository()
        val observation = launch { repo.observeConversation("conversation") }
        try {
            withTimeout(5000) { opened.await() }
            repo.cancel("run")
            withTimeout(1500) { closed.await() }
            val next = backend.sample("running", 1).let { it.copy(id = "next", input = it.input.copy(id = "next-input", runId = "next", sequence = 3),
                output = it.output.copy(id = "next-output", runId = "next", sequence = 4)) }
            backend.current = next
            repo.store(next)
            withTimeout(1500) { nextOpened.await() }
            assertEquals(listOf(4L, 1L), backend.cursors)
        } finally { observation.cancelAndJoin() }
    }

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

    @Test fun sharedRetryPolicyReusesPersistedWriteIdentityAfterLostAcknowledgement() = runBlocking {
        backend.loseNextResponse = true
        val repo = OfflineRepository(database, "profile-a", ReliableBackend(backend, RetryPolicy(pause = {})))
        repo.send("conversation", "记笔记：测试")
        assertEquals(2, backend.requests.size)
        assertEquals(backend.requests[0], backend.requests[1])
        assertEquals(1, backend.writes)
        assertTrue(repo.pending("conversation").first().isEmpty())
    }

    @Test fun malformedReplyKeepsPendingIdentityAfterRestart() = runBlocking {
        val corrupt = object : BackendAdapter by backend {
            override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run {
                backend.createRun(conversationId, messageId, requestKey, text)
                throw BackendFailure(FailureKind.PROTOCOL)
            }
        }
        val repo = OfflineRepository(database, "profile-a", corrupt)
        try { repo.send("conversation", "hello"); fail() } catch (_: BackendFailure) { }
        val pending = repo.pending("conversation").first().single()
        assertFalse(pending.blocked)
        reopen()
        repository().retry(pending.id)
        assertEquals(1, backend.writes)
        assertEquals(backend.requests[0], backend.requests[1])
    }

    @Test fun eventTextAndCursorSurviveDatabaseReopenAndResumeWithoutDuplicates() = runBlocking {
        backend.supportsEvents = true
        val repo = repository()
        repo.store(backend.sample("running", 4))
        val committed = CompletableDeferred<Unit>()
        backend.eventFlow = { flow {
            emit(RunEvent("run", 5, EventKind.MESSAGE, backend.sample().output.copy(status = "streaming", blocks = listOf(MessageBlock("text", "已保存一半")))))
            committed.complete(Unit)
            awaitCancellation()
        } }
        val job = launch {
            RunMonitor(backend).follow({ repo.runs("conversation").first().single() }, repo::store)
        }
        committed.await()
        job.cancelAndJoin()
        reopen()
        val restored = repository()
        assertEquals(5L, restored.runs("conversation").first().single().sequence)
        assertEquals("已保存一半", restored.messages("conversation").first().last().text)
        backend.eventFlow = { cursor -> flow {
            assertEquals(5L, cursor)
            emit(RunEvent("run", 5, EventKind.MESSAGE, backend.sample().output))
            emit(RunEvent("run", 6, EventKind.COMPLETED, backend.sample().output))
        } }
        RunMonitor(backend).follow({ restored.runs("conversation").first().single() }, restored::store)
        assertEquals(listOf(4L, 5L), backend.cursors)
        assertEquals(2, restored.messages("conversation").first().size)
        assertEquals("completed", restored.runs("conversation").first().single().status)
    }

    @Test fun memoryAdapterPassesSharedBusinessContract() = runBlocking { verifyAdapterContract(backend, "character") }

    @Test fun laterRejectionAfterRestartCannotDiscardAnUnconfirmedAcceptedWrite() = runBlocking {
        backend.loseNextResponse = true
        try { repository().send("conversation", "hello"); fail() } catch (_: RemoteFailure) { }
        // Stage 6A could leave a null error if the process died during submission.
        val legacy = database.dao().pending("profile-a", "conversation").single()
        database.dao().updatePending(legacy.copy(error = null))
        reopen()
        backend.forbidden = true
        val pending = repository().pending("conversation").first().single()
        try { repository().retry(pending.id); fail() } catch (_: RemoteFailure) { }
        assertFalse(repository().pending("conversation").first().single().blocked)
        assertEquals(1, backend.writes)
    }
}
