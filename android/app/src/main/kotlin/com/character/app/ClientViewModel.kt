package com.character.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.room.Room
import com.character.app.data.*
import com.character.app.domain.*
import com.character.app.network.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ClientViewModel internal constructor(
    application: Application,
    private val vault: ConnectionStore,
    private val database: ClientDatabase,
    private val backendFactory: (BackendConnection, ConnectionMode) -> BackendAdapter,
    private val mainDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

    // Keep Android's normal factory entry point; tests replace storage/network, never real credentials.
    constructor(application: Application) : this(
        application = application,
        vault = ConnectionVault(application),
        database = Room.databaseBuilder(application, ClientDatabase::class.java, "character-client.db").build(),
        backendFactory = BackendFactory::connect,
        mainDispatcher = Dispatchers.Main.immediate,
    )

    private var repository: CharacterRepository? = null
    private var connectionScope: String? = null
    private var adapter: BackendAdapter? = null
    private val lifetime = SupervisorJob()
    private var session = CoroutineScope(SupervisorJob(lifetime) + mainDispatcher)
    private var chatJob: Job? = null
    private val foreground = MutableStateFlow(true)

    private class Draft(val value: MessageDraft, var state: DraftSaveState)
    private val drafts = mutableMapOf<DraftWriter.Key, Draft>()

    private val mutable = MutableStateFlow(
        ClientState(
            dark = vault.dark,
            polling = vault.mode == ConnectionMode.POLLING,
        )
    )
    val state: StateFlow<ClientState> = mutable.asStateFlow()

    private val draftWriter = DraftWriter(CoroutineScope(lifetime + mainDispatcher)) { key, value, saved ->
        val draft = drafts[key]
        if (draft?.value?.revision == value.revision) {
            draft.state = if (saved) DraftSaveState.SAVED else DraftSaveState.FAILED
            val isCurrentConversation = connectionScope == key.profile &&
                state.value.selected?.id == key.conversation &&
                state.value.draftState != DraftSaveState.LOADING
            if (isCurrentConversation) {
                mutable.update { it.copy(draftState = draft.state) }
            }
        }
    }

    init {
        try {
            val saved = vault.read()
            if (saved != null) {
                attach(BackendConnection(saved.first, saved.second))
            } else {
                val defaultUrl = "http://127.0.0.1:8000/"
                val defaultToken = "VXyaOWUsTIYIJftfvo0qYe26FUizGyPXtGYarIs4-nQ"
                vault.save(defaultUrl, defaultToken)
                attach(BackendConnection(defaultUrl, defaultToken))
            }
        } catch (_: Exception) {
            mutable.update { it.copy(error = "本机连接配置不可用，请重新填写。") }
        }
    }

    // =========================================================================
    // Connection & Session Management
    // =========================================================================

    fun connect(url: String, credential: String) {
        try {
            val connection = BackendConnection(url, credential.trim())
            vault.save(connection.baseUrl, credential.trim())
            attach(connection)
        } catch (_: Exception) {
            mutable.update { it.copy(error = "无法保存连接，请检查地址和访问凭证。") }
        }
    }

    private fun attach(connection: BackendConnection) {
        session.cancel()
        adapter?.close()
        session = CoroutineScope(SupervisorJob(lifetime) + mainDispatcher)

        // D26: both the in-memory edits and persisted drafts are keyed by profile + conversation.
        connectionScope = connection.scope
        com.character.app.characters.VoicePlayer.activeBackendUrl = connection.baseUrl
        val backend = backendFactory(connection, vault.mode)
        adapter = backend
        val repo = OfflineRepository(database, connection.scope, backend)
        repository = repo

        mutable.value = ClientState(
            connected = true,
            url = connection.baseUrl,
            tab = "广场",
            dark = vault.dark,
            polling = vault.mode == ConnectionMode.POLLING,
            canManageCharacters = backend.supportsCharacterManagement,
            canManageProfile = backend.supportsProfile,
        )

        session.launch {
            combine(repo.profile(), repo.pendingProfile()) { profile, pending ->
                profile to pending
            }.collect { (profile, pending) ->
                if (repository === repo) {
                    mutable.update { it.copy(userProfile = profile, pendingProfileChange = pending) }
                }
            }
        }

        session.launch {
            repo.pendingCharacter().collect { pending ->
                if (repository === repo) {
                    mutable.update { it.copy(pendingCharacter = pending) }
                }
            }
        }

        session.launch {
            combine(repo.characters(), repo.conversations(), repo.notes()) { characters, conversations, notes ->
                Triple(characters, conversations, notes)
            }.collect { (characters, conversations, notes) ->
                if (repository === repo) {
                    mutable.update {
                        it.copy(
                            characters = characters,
                            conversations = conversations,
                            notes = notes,
                        )
                    }
                }
            }
        }

        action {
            refreshHome()
            refreshNotes()
        }
    }

    fun disconnect() {
        vault.clear()
        session.cancel()
        adapter?.close()
        adapter = null
        repository = null
        connectionScope = null
        drafts.clear()
        mutable.value = ClientState(
            dark = vault.dark,
            polling = vault.mode == ConnectionMode.POLLING,
        )
    }

    fun mode(polling: Boolean) {
        try {
            vault.mode = if (polling) ConnectionMode.POLLING else ConnectionMode.REALTIME
            vault.read()?.let { attach(BackendConnection(it.first, it.second)) }
            mutable.update { it.copy(polling = polling) }
        } catch (_: Exception) {
            mutable.update { it.copy(error = "无法切换连接方式，请重试。") }
        }
    }

    // =========================================================================
    // Theme & Navigation
    // =========================================================================

    fun theme(dark: Boolean) {
        vault.dark = dark
        mutable.update { it.copy(dark = dark) }
    }

    fun tab(tab: String) {
        back()
        mutable.update { it.copy(tab = tab) }
        if (tab == "笔记") {
            refreshNotes()
        }
        if (tab == "我的" && state.value.canManageProfile) {
            refreshProfile()
        }
    }

    fun back() {
        chatJob?.cancel()
        mutable.update {
            it.copy(
                selected = null,
                messages = emptyList(),
                pending = emptyList(),
                runs = emptyList(),
                draft = "",
            )
        }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun foreground(value: Boolean) {
        foreground.value = value
    }

    // =========================================================================
    // Profile Operations
    // =========================================================================

    fun refreshProfile() = action {
        refreshProfile()
    }

    fun editProfile() {
        if (!state.value.busy && state.value.pendingProfileChange == null) {
            mutable.update { it.copy(profileEditor = it.userProfile) }
        }
    }

    fun changeProfile(profile: UserProfile) {
        if (!state.value.busy && state.value.pendingProfileChange == null && state.value.profileEditor?.version == profile.version) {
            mutable.update { it.copy(profileEditor = profile) }
        }
    }

    fun closeProfileEditor() {
        if (!state.value.busy) {
            mutable.update { it.copy(profileEditor = null, error = null) }
        }
    }

    fun saveProfile() {
        val profile = state.value.profileEditor ?: return
        action {
            saveProfile(profile)
            if (repository === this) {
                mutable.update { it.copy(profileEditor = null) }
            }
        }
    }

    fun retryProfile() = action {
        retryProfile()
        if (repository === this) {
            mutable.update { it.copy(profileEditor = null) }
        }
    }

    fun reloadProfile() = action {
        reloadProfile()
        if (repository === this) {
            mutable.update { it.copy(profileEditor = null) }
        }
    }

    fun resetProfile() {
        val profile = state.value.userProfile ?: return
        action {
            resetProfile(profile.version)
        }
    }

    // =========================================================================
    // Character Management Operations
    // =========================================================================

    fun newCharacter() {
        val repo = repository
        if (repo == null) {
            mutable.update {
                it.copy(
                    characterEditor = CharacterDraft(
                        id = UUID.randomUUID().toString(),
                        name = "",
                        summary = "",
                        systemPrompt = "",
                        notesEnabled = false,
                        version = 0,
                    )
                )
            }
            return
        }
        session.launch {
            try {
                val pending = repo.pendingCharacter().first()
                mutable.update {
                    it.copy(
                        characterEditor = pending ?: CharacterDraft(
                            id = UUID.randomUUID().toString(),
                            name = "",
                            summary = "",
                            systemPrompt = "",
                            notesEnabled = false,
                            version = 0,
                        )
                    )
                }
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        characterEditor = CharacterDraft(
                            id = UUID.randomUUID().toString(),
                            name = "",
                            summary = "",
                            systemPrompt = "",
                            notesEnabled = false,
                            version = 0,
                        )
                    )
                }
            }
        }
    }

    fun editCharacter(character: Character) = action {
        val pending = pendingCharacter().first()
        val definition = pending ?: characterDefinition(character.id)
        mutable.update { it.copy(characterEditor = definition) }
    }

    fun changeCharacter(draft: CharacterDraft) {
        if (!state.value.busy && state.value.pendingCharacter == null && state.value.characterEditor?.id == draft.id) {
            mutable.update { it.copy(characterEditor = draft) }
        }
    }

    fun closeCharacterEditor() {
        if (!state.value.busy) {
            mutable.update { it.copy(characterEditor = null, error = null) }
        }
    }

    fun saveCharacter() {
        val draft = state.value.characterEditor ?: return
        action {
            saveCharacter(draft)
            mutable.update { it.copy(characterEditor = null) }
        }
    }

    fun reloadCharacter() {
        val draft = state.value.characterEditor ?: return
        action {
            val saved = reloadCharacter(draft.id)
            mutable.update {
                it.copy(
                    characterEditor = saved,
                    error = if (saved == null) "该角色已被删除，原聊天记录仍保留。" else null,
                )
            }
        }
    }

    fun deleteCharacter(character: Character) = action {
        deleteCharacter(character.id)
    }

    // =========================================================================
    // Draft Management
    // =========================================================================

    fun draft(text: String) {
        val conversation = state.value.selected ?: return
        val repo = repository ?: return
        val profile = connectionScope ?: return
        if (state.value.draftState == DraftSaveState.LOADING) return

        val draft = Draft(MessageDraft(UUID.randomUUID().toString(), text), DraftSaveState.SAVING)
        val key = DraftWriter.Key(profile, conversation.id)
        drafts[key] = draft
        mutable.update { it.copy(draft = text, draftState = draft.state) }
        draftWriter.save(key, repo, draft.value)
    }

    fun retryDraftSave() {
        val conversation = state.value.selected ?: return
        val repo = repository ?: return
        val profile = connectionScope ?: return
        val key = DraftWriter.Key(profile, conversation.id)
        val draft = drafts[key] ?: return
        draft.state = DraftSaveState.SAVING
        mutable.update { it.copy(draftState = draft.state) }
        draftWriter.save(key, repo, draft.value)
    }

    // =========================================================================
    // Conversation & Chat Operations
    // =========================================================================

    fun refreshHome() = action {
        refreshHome()
    }

    fun refreshNotes() = action {
        refreshNotes()
    }

    fun create(character: Character) = action {
        val id = createConversation(character.id)
        refreshHome()
        val conversation = conversations().first().first { it.id == id }
        open(conversation)
    }

    fun renameConversation(conversationId: String, newTitle: String) = action {
        val repo = repository ?: return@action
        val conv = conversations().first().firstOrNull { it.id == conversationId } ?: return@action
        val updated = repo.renameConversation(conversationId, newTitle, conv.version)
        mutable.update { current ->
            if (current.selected?.id == conversationId || current.selected == null) {
                current.copy(selected = updated)
            } else {
                current
            }
        }
        refreshHome()
    }

    fun reconnectChat() {
        state.value.selected?.let(::open)
    }

    fun open(conversation: Conversation) {
        val repo = repository ?: return
        val profile = connectionScope ?: return
        val key = DraftWriter.Key(profile, conversation.id)
        chatJob?.cancel()

        val cachedDraft = drafts[key]
        mutable.update {
            it.copy(
                selected = conversation,
                messages = emptyList(),
                pending = emptyList(),
                runs = emptyList(),
                draft = cachedDraft?.value?.text.orEmpty(),
                draftState = DraftSaveState.LOADING,
            )
        }

        chatJob = session.launch {
            // Draft restoration task
            launch {
                try {
                    val previousSaveSucceeded = draftWriter.flush(key)
                    // Always re-read: a cancelled send may already have consumed the draft atomically.
                    var loaded = if (!previousSaveSucceeded && cachedDraft != null) {
                        cachedDraft
                    } else {
                        Draft(
                            value = repo.draft(conversation.id) ?: MessageDraft(UUID.randomUUID().toString(), ""),
                            state = if (previousSaveSucceeded) DraftSaveState.SAVED else DraftSaveState.FAILED,
                        )
                    }

                    // A send can finish while the read is suspended; never restore its older snapshot.
                    if (cachedDraft != null && drafts[key] !== cachedDraft) {
                        loaded = drafts[key] ?: Draft(
                            MessageDraft(UUID.randomUUID().toString(), ""),
                            DraftSaveState.SAVED,
                        )
                    }

                    if (repository === repo && state.value.selected?.id == conversation.id) {
                        drafts[key] = loaded
                        mutable.update {
                            it.copy(
                                draft = loaded.value.text,
                                draftState = loaded.state,
                                error = if (previousSaveSucceeded || cachedDraft != null) it.error else "此前草稿保存失败，已恢复最近保存的内容。",
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (repository === repo && state.value.selected?.id == conversation.id) {
                        mutable.update { it.copy(error = "无法读取本机草稿，请点重新连接后重试。") }
                    }
                }
            }

            // Message / Outbox / Runs observation task
            launch {
                combine(
                    repo.messages(conversation.id),
                    repo.pending(conversation.id),
                    repo.runs(conversation.id),
                ) { messages, pending, runs ->
                    Triple(messages, pending, runs)
                }.collect { (messages, pending, runs) ->
                    if (repository === repo && state.value.selected?.id == conversation.id) {
                        mutable.update {
                            it.copy(
                                messages = messages,
                                pending = pending,
                                runs = runs,
                            )
                        }
                    }
                }
            }

            // Realtime observation with background pause
            // collectLatest cancels sockets/backoff immediately on backgrounding. Foreground re-reads Room.
            foreground.collectLatest { visible ->
                if (!visible) return@collectLatest
                try {
                    repo.observeConversation(conversation.id)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (repository === repo && state.value.selected?.id == conversation.id) {
                        mutable.update { it.copy(error = userMessage(error) + " 可点重新连接继续同步。") }
                    }
                }
            }
        }
    }

    fun send() {
        val current = state.value
        val conversation = current.selected ?: return
        if (current.busy || current.draft.isBlank() || current.draftState == DraftSaveState.LOADING) return

        val profile = connectionScope ?: return
        val key = DraftWriter.Key(profile, conversation.id)
        val submitted = drafts[key] ?: return

        action {
            if (!draftWriter.flush(key)) {
                mutable.update { it.copy(error = "草稿尚未保存，请先重试保存。") }
                return@action
            }

            val previousPending = pending(conversation.id).first().map { it.id }.toSet()
            var acknowledged = false
            try {
                send(conversation.id, submitted.value.text, submitted.value.revision)
                acknowledged = true
            } finally {
                // Only this submission can consume this edit. Re-typing identical text creates a new revision.
                // Historical text matches or an already-existing outbox item are not an acknowledgement.
                val queued = acknowledged || pending(conversation.id).first().any { it.id !in previousPending }
                if (queued && repository === this && drafts[key]?.value?.revision == submitted.value.revision) {
                    drafts.remove(key)
                    if (state.value.selected?.id == conversation.id && state.value.draftState != DraftSaveState.LOADING) {
                        mutable.update { it.copy(draft = "", draftState = DraftSaveState.SAVED) }
                    }
                }
            }
        }
    }

    fun regenerate() {
        val current = state.value
        val conversation = current.selected ?: return
        val lastUserMessage = current.messages.lastOrNull { it.role == "user" } ?: return
        if (current.busy || current.runs.any { !it.terminal }) return
        action {
            send(conversation.id, lastUserMessage.text)
        }
    }

    fun sendText(text: String) {
        val current = state.value
        val conversation = current.selected ?: return
        if (current.busy || current.runs.any { !it.terminal }) return
        action {
            send(conversation.id, text)
        }
    }

    fun retry(id: String) = action {
        retry(id)
    }

    fun dismiss(id: String) = action {
        dismiss(id)
    }

    fun cancel(id: String) {
        val repo = repository ?: return
        session.launch {
            try {
                repo.cancel(id)
                repo.refreshNotes()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (repository === repo) {
                    mutable.update { it.copy(error = userMessage(error)) }
                }
            }
        }
    }

    // =========================================================================
    // Plaza Direct Actions
    // =========================================================================

    fun startPlazaCharacter(plazaChar: com.character.app.characters.PlazaCharacter) = action {
        val existing = state.value.characters.find { it.name == plazaChar.name || it.id == plazaChar.id }
        if (existing != null) {
            val id = createConversation(existing.id)
            refreshHome()
            val conversation = conversations().first().first { it.id == id }
            open(conversation)
        } else if (state.value.canManageCharacters) {
            val draft = CharacterDraft(
                id = UUID.randomUUID().toString(),
                name = plazaChar.name,
                summary = plazaChar.quote,
                systemPrompt = plazaChar.systemPrompt,
                notesEnabled = true,
                version = 0,
            )
            saveCharacter(draft)
            refreshHome()
            val created = characters().first().find { it.name == plazaChar.name }
            if (created != null) {
                val id = createConversation(created.id)
                refreshHome()
                val conversation = conversations().first().first { it.id == id }
                open(conversation)
            }
        } else {
            val fallback = state.value.characters.firstOrNull()
            if (fallback != null) {
                val id = createConversation(fallback.id)
                refreshHome()
                val conversation = conversations().first().first { it.id == id }
                open(conversation)
            }
        }
    }

    fun sendPlazaDirectMessage(plazaChar: com.character.app.characters.PlazaCharacter, text: String) = action {
        val existing = state.value.characters.find { it.name == plazaChar.name || it.id == plazaChar.id }
        val charId = if (existing != null) {
            existing.id
        } else if (state.value.canManageCharacters) {
            val draft = CharacterDraft(
                id = UUID.randomUUID().toString(),
                name = plazaChar.name,
                summary = plazaChar.quote,
                systemPrompt = plazaChar.systemPrompt,
                notesEnabled = true,
                version = 0,
            )
            saveCharacter(draft)
            refreshHome()
            characters().first().find { it.name == plazaChar.name }?.id
        } else {
            state.value.characters.firstOrNull()?.id
        }

        if (charId != null) {
            val conv = conversations().first().find { it.characterId == charId }
            val convId = conv?.id ?: createConversation(charId)
            refreshHome()
            val conversation = conversations().first().first { it.id == convId }
            open(conversation)
            send(convId, text)
        }
    }

    // =========================================================================
    // Internal Action Executor & Lifecycle
    // =========================================================================

    private fun action(block: suspend CharacterRepository.() -> Unit) {
        val repo = repository ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        session.launch {
            try {
                repo.block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (repository === repo) {
                    mutable.update { it.copy(error = userMessage(error)) }
                }
            } finally {
                if (repository === repo) {
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    override fun onCleared() {
        session.cancel()
        adapter?.close()
        draftWriter.close()
        lifetime.complete() // Drain accepted local edits; previous network sessions are already cancelled.
        // Cancellation is asynchronous; include previous sessions still unwinding after a profile switch.
        // Closing Room only after all children finish avoids racing an in-flight database query.
        lifetime.invokeOnCompletion { database.close() }
    }
}
