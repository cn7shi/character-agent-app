package com.character.app.domain

import kotlinx.coroutines.flow.Flow

data class Character(val id: String, val name: String, val summary: String, val version: Int)
data class Conversation(val id: String, val characterId: String, val title: String, val updatedAt: String)
data class MessageBlock(val kind: String, val text: String)
data class Message(val id: String, val conversationId: String, val runId: String, val sequence: Long,
                   val role: String, val blocks: List<MessageBlock>, val status: String, val updatedAt: String) {
    val text: String get() = blocks.joinToString("\n") { it.text }
}
data class Run(val id: String, val conversationId: String, val status: String, val sequence: Long,
               val input: Message, val output: Message, val simulated: Boolean, val error: String?) {
    val terminal: Boolean get() = status in setOf("completed", "failed", "cancelled")
}
data class Note(val id: String, val conversationId: String, val title: String, val content: String, val createdAt: String)
data class PendingMessage(val id: String, val conversationId: String, val text: String, val error: String?, val blocked: Boolean)
data class Page<T>(val items: List<T>, val nextCursor: String?)

/** D18: screens depend on these business objects, never Room rows or transport JSON. */
interface CharacterRepository {
    fun characters(): Flow<List<Character>>
    fun conversations(): Flow<List<Conversation>>
    fun messages(conversationId: String): Flow<List<Message>>
    fun pending(conversationId: String): Flow<List<PendingMessage>>
    fun runs(conversationId: String): Flow<List<Run>>
    fun notes(): Flow<List<Note>>
    suspend fun refreshHome()
    suspend fun refreshConversation(conversationId: String)
    suspend fun refreshNotes()
    suspend fun createConversation(characterId: String): String
    suspend fun send(conversationId: String, text: String)
    suspend fun retry(messageId: String)
    suspend fun dismiss(messageId: String)
    suspend fun poll(conversationId: String)
    suspend fun cancel(runId: String)
}

/** The backend protocol can change without changing the repository or presentation contracts. */
interface BackendGateway {
    suspend fun characters(cursor: String?): Page<Character>
    suspend fun conversations(cursor: String?): Page<Conversation>
    suspend fun messages(conversationId: String, cursor: String?): Page<Message>
    suspend fun notes(cursor: String?): Page<Note>
    suspend fun createConversation(characterId: String, requestKey: String): Conversation
    suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run
    suspend fun run(runId: String): Run
    suspend fun cancel(runId: String): Run
}
