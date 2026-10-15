package com.character.app.data

import androidx.room.withTransaction
import com.character.app.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

fun userMessage(error: Throwable): String = when (error) {
    is BackendFailure -> when (error.kind) {
        FailureKind.AUTHENTICATION, FailureKind.FORBIDDEN -> "连接凭证无效，请在设置中检查。"
        FailureKind.NOT_FOUND -> "记录不存在或不属于当前连接。"
        FailureKind.CONFLICT -> "内容已更新或操作冲突，请读取最新内容后再试。"
        FailureKind.INVALID_REQUEST -> "内容不符合要求，请检查后重新发送。"
        FailureKind.THROTTLED -> "请求较多，请稍后重试。"
        FailureKind.PROTOCOL, FailureKind.UNSUPPORTED -> "服务返回了无法识别的数据，请检查服务版本。"
        else -> "暂时无法连接服务。已缓存内容仍可查看，请稍后重试。"
    }
    is IllegalArgumentException -> "请检查输入内容或连接设置。"
    is IllegalStateException -> "请先处理当前待发送消息或等待回复结束。"
    else -> "暂时无法完成操作，请重试。"
}

class OfflineRepository(private val db: ClientDatabase, private val scope: String, private val remote: BackendAdapter) : CharacterRepository {
    private val dao = db.dao()
    private val gate = Mutex()
    private val profiles = ProfileCache(db, scope, remote)
    private val outbox = MessageOutbox(db, scope, remote)
    private val syncer = ConversationSyncer(db, scope, remote, outbox::store, ::refreshNotesLocked)
    override fun profile() = profiles.observe()
    override fun pendingProfile() = profiles.observePending()
    override suspend fun refreshProfile() = profiles.refresh()
    override suspend fun saveProfile(profile: UserProfile) = profiles.save(profile)
    override suspend fun resetProfile(version: Int) = profiles.reset(version)
    override suspend fun retryProfile() = profiles.retry()
    override suspend fun reloadProfile() = profiles.reload()
    private fun <T> watch(kind: String, read: (JSONObject) -> T) = dao.observe(scope, kind).map { rows -> rows.map { read(JSONObject(it.payload)) } }
    override fun pendingCharacter() = watch("character_write", JsonCodec::characterDraft).map { it.singleOrNull() }
    override suspend fun characterDefinition(id: String) = remote.characterDefinition(id)
    override suspend fun saveCharacter(draft: CharacterDraft) = gate.withLock {
        val normalized = draft.copy(name = draft.name.trim(), summary = draft.summary.trim(), systemPrompt = draft.systemPrompt.trim())
        fun String.count() = codePointCount(0, length)
        require(normalized.name.count() in 1..100 && normalized.summary.count() <= 500 && normalized.systemPrompt.count() in 1..8000)
        val pending = dao.get(scope, "character_write", "pending")?.let { JsonCodec.characterDraft(JSONObject(it.payload)) }
        check(pending == null || pending == normalized)
        // D29: persist the exact ID, version and content before HTTP. Unknown results are retried
        // unchanged even after restart; changing the form requires an explicit server reload.
        dao.put(listOf(Snapshot(scope, "character_write", "pending", JsonCodec.encode(normalized).toString(), "")))
        val saved = remote.saveCharacter(normalized)
        if (saved.id != draft.id || saved.version != draft.version + 1) throw BackendFailure(FailureKind.PROTOCOL)
        db.withTransaction {
            dao.put(listOf(row(Character(saved.id, saved.name, saved.summary, saved.version, true))))
            dao.clearKind(scope, "character_write")
        }
    }
    override suspend fun reloadCharacter(id: String): CharacterDraft? = gate.withLock {
        val pending = dao.get(scope, "character_write", "pending")?.let { JsonCodec.characterDraft(JSONObject(it.payload)) }
        val current = try { remote.characterDefinition(id) } catch (error: BackendFailure) {
            if (error.kind != FailureKind.NOT_FOUND) throw error
            // An absent unconfirmed creation may still be in flight. Keep its identity/content;
            // a successful GET can reconcile even if another device already edited version 1.
            if (pending?.id == id && pending.version == 0) throw error
            null
        }
        db.withTransaction {
            if (pending?.id == id) dao.clearKind(scope, "character_write")
            if (current == null) dao.deleteSnapshot(scope, "characters", id)
            else dao.put(listOf(row(Character(current.id, current.name, current.summary, current.version, true))))
        }
        current
    }
    override suspend fun deleteCharacter(id: String) = gate.withLock {
        check(dao.list(scope, "character_write").isEmpty())
        remote.deleteCharacter(id)
        dao.deleteSnapshot(scope, "characters", id)
    }
    override fun characters() = watch("characters", JsonCodec::character)
    override fun conversations() = watch("conversations", JsonCodec::conversation)

    override fun messages(conversationId: String) = watch("messages:$conversationId", JsonCodec::message).map { it.sortedBy(Message::sequence) }

    override fun runs(conversationId: String) = watch("runs:$conversationId", JsonCodec::run)
    override fun notes() = watch("notes", JsonCodec::note)

    override fun pending(conversationId: String) = outbox.pending(conversationId)

    override suspend fun draft(conversationId: String) = dao.draft(scope, conversationId)?.let { MessageDraft(it.revision, it.text) }
    override suspend fun saveDraft(conversationId: String, draft: MessageDraft) {
        require(draft.revision.isNotBlank())
        // Local editing must not wait for a slow network request holding the submission gate.
        if (draft.text.isEmpty()) dao.clearDraft(scope, conversationId)
        else dao.saveDraft(DraftRow(scope, conversationId, draft.revision, draft.text))
    }

    private fun row(c: Character) = Snapshot(scope, "characters", c.id, JsonCodec.encode(c).toString(), c.name)
    private fun row(c: Conversation) = Snapshot(scope, "conversations", c.id, JsonCodec.encode(c).toString(), c.updatedAt)
    private fun row(m: Message) = Snapshot(scope, "messages:${m.conversationId}", m.id, JsonCodec.encode(m).toString(), m.sequence.toString().padStart(20, '0'))
    private fun row(n: Note) = Snapshot(scope, "notes", n.id, JsonCodec.encode(n).toString(), n.createdAt)

    override suspend fun refreshHome() = gate.withLock {
        val characters = fetchAllPages(remote::characters).map(::row)
        val conversations = fetchAllPages(remote::conversations).map(::row)
        // A complete successful list is authoritative, so deleted roles also disappear from cache.
        // Never clear before all pages succeed; offline refresh must preserve the previous snapshot.
        db.withTransaction {
            dao.clearKind(scope, "characters")
            dao.put(characters)
            dao.put(conversations)
        }
    }
    override suspend fun refreshNotes() = gate.withLock { refreshNotesLocked() }
    private suspend fun refreshNotesLocked() { dao.put(fetchAllPages(remote::notes).map(::row)) }

    override suspend fun refreshConversation(conversationId: String) = syncer.refreshConversation(conversationId)

    override suspend fun createConversation(characterId: String): String = gate.withLock {
        val pending = dao.conversationRequest(scope, characterId) ?: ConversationRequest(scope, characterId, UUID.randomUUID().toString()).also { dao.putConversationRequest(it) }
        val conversation = remote.createConversation(characterId, pending.requestKey)
        db.withTransaction {
            dao.put(listOf(row(conversation)))
            dao.deleteConversationRequest(scope, characterId)
        }
        conversation.id
    }

    override suspend fun renameConversation(
        conversationId: String,
        title: String,
        expectedVersion: Int,
    ): Conversation = gate.withLock {
        val normalized = title.trim()
        fun String.count() = codePointCount(0, length)
        require(normalized.count() in 1..100)
        val updated = remote.renameConversation(conversationId, normalized, expectedVersion)
        db.withTransaction {
            dao.put(listOf(row(updated)))
        }
        updated
    }

    override suspend fun send(conversationId: String, text: String, draftRevision: String?) =
        outbox.send(conversationId, text, draftRevision)

    override suspend fun retry(messageId: String) = outbox.retry(messageId)

    override suspend fun dismiss(messageId: String) = outbox.dismiss(messageId)

    internal suspend fun store(run: Run) = outbox.store(run)

    override suspend fun poll(conversationId: String) = syncer.poll(conversationId)

    override suspend fun cancel(runId: String) = syncer.cancel(runId)

    override suspend fun observeConversation(conversationId: String) =
        syncer.observeConversation(conversationId, runs(conversationId))
}
