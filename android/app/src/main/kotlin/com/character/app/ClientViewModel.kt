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
    val dark: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val characters: List<Character> = emptyList(), val conversations: List<Conversation> = emptyList(),
    val notes: List<Note> = emptyList(), val selected: Conversation? = null,
    val messages: List<Message> = emptyList(), val pending: List<PendingMessage> = emptyList(),
    val runs: List<Run> = emptyList(), val draft: String = ""
)

class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ConnectionVault(application)
    private val database = Room.databaseBuilder(application, ClientDatabase::class.java, "character-client.db").build()
    private var repository: CharacterRepository? = null
    private var session = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var chatJob: Job? = null
    private var foreground = true
    private val drafts = mutableMapOf<String, String>()
    private val mutable = MutableStateFlow(ClientState(dark = vault.dark))
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
        session = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        drafts.clear()
        val repo = OfflineRepository(database, connection.scope, HttpBackend(HttpTransport(connection)))
        repository = repo
        mutable.value = ClientState(connected = true, url = connection.baseUrl, tab = "角色", dark = vault.dark)
        session.launch {
            combine(repo.characters(), repo.conversations(), repo.notes()) { characters, conversations, notes -> Triple(characters, conversations, notes) }
                .collect { (characters, conversations, notes) -> mutable.update { it.copy(characters = characters, conversations = conversations, notes = notes) } }
        }
        action { refreshHome(); refreshNotes() }
    }
    fun disconnect() {
        vault.clear()
        session.cancel()
        repository = null
        drafts.clear()
        mutable.value = ClientState(dark = vault.dark)
    }
    fun theme(dark: Boolean) { vault.dark = dark; mutable.update { it.copy(dark = dark) } }
    fun tab(tab: String) { back(); mutable.update { it.copy(tab = tab) }; if (tab == "笔记") refreshNotes() }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun foreground(value: Boolean) { foreground = value }
    fun draft(text: String) { state.value.selected?.let { drafts[it.id] = text }; mutable.update { it.copy(draft = text) } }
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
        mutable.update { it.copy(selected = conversation, messages = emptyList(), pending = emptyList(), runs = emptyList(), draft = drafts[conversation.id].orEmpty()) }
        chatJob = session.launch {
            launch {
                combine(repo.messages(conversation.id), repo.pending(conversation.id), repo.runs(conversation.id)) { messages, pending, runs -> Triple(messages, pending, runs) }
                    .collect { (messages, pending, runs) -> mutable.update { it.copy(messages = messages, pending = pending, runs = runs) } }
            }
            try { repo.refreshConversation(conversation.id) }
            catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(error = userMessage(error)) } }
            // D21: stage 6A polls snapshots; leaving a page stops observation, never cancels server execution.
            while (isActive) {
                delay(1000)
                if (!foreground) continue
                try { repo.poll(conversation.id) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(error = userMessage(error)) }
                    delay(4000)
                }
            }
        }
    }
    fun send() {
        val current = state.value
        val conversation = current.selected ?: return
        if (current.busy || current.draft.isBlank()) return
        action {
            try { send(conversation.id, current.draft) }
            finally {
                // Keep text on local validation failure, clear once it is either queued or acknowledged.
                if (pending(conversation.id).first().isNotEmpty() || messages(conversation.id).first().any { it.role == "user" && it.text == current.draft.trim() }) {
                    drafts.remove(conversation.id)
                    if (state.value.selected?.id == conversation.id) mutable.update { it.copy(draft = "") }
                }
            }
        }
    }
    fun retry(id: String) = action { retry(id) }
    fun dismiss(id: String) = action { dismiss(id) }
    fun cancel(id: String) = action { cancel(id); refreshNotes() }
    private fun action(block: suspend CharacterRepository.() -> Unit) {
        val repo = repository ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        session.launch {
            try { repo.block() }
            catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(error = userMessage(error)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { session.cancel(); database.close() }
}
