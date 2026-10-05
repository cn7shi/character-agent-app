package com.character.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.room.Room
import com.character.app.data.*
import com.character.app.domain.*
import com.character.app.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ClientState(
    val connected: Boolean = false, val url: String = "http://127.0.0.1:8000/", val tab: String = "设置",
    val dark: Boolean = true, val busy: Boolean = false, val error: String? = null, val polling: Boolean = false,
    val characters: List<Character> = emptyList(), val conversations: List<Conversation> = emptyList(),
    val notes: List<Note> = emptyList(), val selected: Conversation? = null,
    val messages: List<Message> = emptyList(), val pending: List<PendingMessage> = emptyList(),
    val runs: List<Run> = emptyList(), val draft: String = ""
)

class ClientViewModel internal constructor(
    application: Application,
    private val vault: ConnectionStore,
    private val database: ClientDatabase,
    private val backendFactory: (BackendConnection, ConnectionMode) -> BackendAdapter,
    private val mainDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    // Keep Android's normal factory entry point; tests replace storage/network, never real credentials.
    constructor(application: Application) : this(application, ConnectionVault(application),
        Room.databaseBuilder(application, ClientDatabase::class.java, "character-client.db").build(),
        BackendFactory::connect, Dispatchers.Main.immediate)
    private var repository: CharacterRepository? = null
    private var connectionScope: String? = null
    private var adapter: BackendAdapter? = null
    private val lifetime = SupervisorJob()
    private var session = CoroutineScope(SupervisorJob(lifetime) + mainDispatcher)
    private var chatJob: Job? = null
    private val foreground = MutableStateFlow(true)
    private class Draft(val text: String)
    private val drafts = mutableMapOf<String, Draft>()
    private val mutable = MutableStateFlow(ClientState(dark = vault.dark, polling = vault.mode == ConnectionMode.POLLING))
    val state = mutable.asStateFlow()

    init {
        try { vault.read()?.let { attach(BackendConnection(it.first, it.second)) } }
        catch (_: Exception) { mutable.update { it.copy(error = "本机连接配置不可用，请重新填写。") } }
    }
    fun connect(url: String, credential: String) {
        try {
            val connection = BackendConnection(url, credential.trim())
            vault.save(connection.baseUrl, credential.trim())
            attach(connection)
        } catch (_: Exception) { mutable.update { it.copy(error = "无法保存连接，请检查地址和访问凭证。") } }
    }
    private fun attach(connection: BackendConnection) {
        session.cancel()
        adapter?.close()
        session = CoroutineScope(SupervisorJob(lifetime) + mainDispatcher)
        // D25: changing transport within one profile must not discard unsent input.
        if (connectionScope != connection.scope) drafts.clear()
        connectionScope = connection.scope
        val backend = backendFactory(connection, vault.mode)
        adapter = backend
        val repo = OfflineRepository(database, connection.scope, backend)
        repository = repo
        mutable.value = ClientState(connected = true, url = connection.baseUrl, tab = "角色", dark = vault.dark, polling = vault.mode == ConnectionMode.POLLING)
        session.launch {
            combine(repo.characters(), repo.conversations(), repo.notes()) { characters, conversations, notes -> Triple(characters, conversations, notes) }
                .collect { (characters, conversations, notes) -> if (repository === repo) mutable.update { it.copy(characters = characters, conversations = conversations, notes = notes) } }
        }
        action { refreshHome(); refreshNotes() }
    }
    fun disconnect() {
        vault.clear()
        session.cancel()
        adapter?.close()
        adapter = null
        repository = null
        connectionScope = null
        drafts.clear()
        mutable.value = ClientState(dark = vault.dark, polling = vault.mode == ConnectionMode.POLLING)
    }
    fun theme(dark: Boolean) { vault.dark = dark; mutable.update { it.copy(dark = dark) } }
    fun tab(tab: String) { back(); mutable.update { it.copy(tab = tab) }; if (tab == "笔记") refreshNotes() }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun foreground(value: Boolean) { foreground.value = value }
    fun mode(polling: Boolean) {
        try {
            vault.mode = if (polling) ConnectionMode.POLLING else ConnectionMode.REALTIME
            vault.read()?.let { attach(BackendConnection(it.first, it.second)) }
            mutable.update { it.copy(polling = polling) }
        } catch (_: Exception) { mutable.update { it.copy(error = "无法切换连接方式，请重试。") } }
    }
    fun reconnectChat() { state.value.selected?.let(::open) }
    fun draft(text: String) { state.value.selected?.let { drafts[it.id] = Draft(text) }; mutable.update { it.copy(draft = text) } }
    fun back() { chatJob?.cancel(); mutable.update { it.copy(selected = null, messages = emptyList(), pending = emptyList(), runs = emptyList(), draft = "") } }
    fun refreshHome() = action { refreshHome() }
    fun refreshNotes() = action { refreshNotes() }
    fun create(character: Character) = action {
        val id = createConversation(character.id)
        refreshHome()
        val conversation = conversations().first().first { it.id == id }
        open(conversation)
    }
    fun open(conversation: Conversation) {
        val repo = repository ?: return
        chatJob?.cancel()
        mutable.update { it.copy(selected = conversation, messages = emptyList(), pending = emptyList(), runs = emptyList(), draft = drafts[conversation.id]?.text.orEmpty()) }
        chatJob = session.launch {
            launch {
                combine(repo.messages(conversation.id), repo.pending(conversation.id), repo.runs(conversation.id)) { messages, pending, runs -> Triple(messages, pending, runs) }
                    .collect { (messages, pending, runs) ->
                        if (repository === repo && state.value.selected?.id == conversation.id)
                            mutable.update { it.copy(messages = messages, pending = pending, runs = runs) }
                    }
            }
            // collectLatest cancels sockets/backoff immediately on backgrounding. Foreground re-reads Room.
            foreground.collectLatest { visible ->
                if (!visible) return@collectLatest
                try { repo.observeConversation(conversation.id) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (repository === repo && state.value.selected?.id == conversation.id)
                        mutable.update { it.copy(error = userMessage(error) + " 可点重新连接继续同步。") }
                }
            }
        }
    }
    fun send() {
        val current = state.value
        val conversation = current.selected ?: return
        if (current.busy || current.draft.isBlank()) return
        val submitted = drafts[conversation.id] ?: return
        action {
            val previousPending = pending(conversation.id).first().map { it.id }.toSet()
            var acknowledged = false
            try { send(conversation.id, submitted.text); acknowledged = true }
            finally {
                // Only this submission can consume this edit. Re-typing even identical text creates a new Draft.
                // Historical text matches or an already-existing outbox item are not an acknowledgement.
                val queued = acknowledged || pending(conversation.id).first().any { it.id !in previousPending }
                if (queued && repository === this && drafts[conversation.id] === submitted) {
                    drafts.remove(conversation.id)
                    if (state.value.selected?.id == conversation.id) mutable.update { it.copy(draft = "") }
                }
            }
        }
    }
    fun retry(id: String) = action { retry(id) }
    fun dismiss(id: String) = action { dismiss(id) }
    fun cancel(id: String) {
        val repo = repository ?: return
        session.launch {
            try { repo.cancel(id); repo.refreshNotes() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (repository === repo) mutable.update { it.copy(error = userMessage(error)) }
            }
        }
    }
    private fun action(block: suspend CharacterRepository.() -> Unit) {
        val repo = repository ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        session.launch {
            try { repo.block() }
            catch (error: Exception) { if (error is CancellationException) throw error; if (repository === repo) mutable.update { it.copy(error = userMessage(error)) } }
            finally { if (repository === repo) mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() {
        lifetime.cancel()
        adapter?.close()
        // Cancellation is asynchronous; include previous sessions still unwinding after a profile switch.
        // Closing Room only after all children finish avoids racing an in-flight database query.
        lifetime.invokeOnCompletion { database.close() }
    }
}
