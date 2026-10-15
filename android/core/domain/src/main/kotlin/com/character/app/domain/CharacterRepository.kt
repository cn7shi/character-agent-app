package com.character.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Clean domain repository interface.
 * Presentation and domain interact with these business objects, decoupled from Room rows or transport JSON.
 */
interface CharacterRepository {

    // --- Profile Operations ---
    fun profile(): Flow<UserProfile?>
    fun pendingProfile(): Flow<ProfileChange?>
    suspend fun refreshProfile()
    suspend fun saveProfile(profile: UserProfile)
    suspend fun resetProfile(version: Int)
    suspend fun retryProfile()
    suspend fun reloadProfile(): UserProfile

    // --- Character Operations ---
    fun pendingCharacter(): Flow<CharacterDraft?>
    suspend fun characterDefinition(id: String): CharacterDraft
    suspend fun saveCharacter(draft: CharacterDraft)
    suspend fun reloadCharacter(id: String): CharacterDraft?
    suspend fun deleteCharacter(id: String)

    // --- Local Flow Observations ---
    fun characters(): Flow<List<Character>>
    fun conversations(): Flow<List<Conversation>>
    fun messages(conversationId: String): Flow<List<Message>>
    fun pending(conversationId: String): Flow<List<PendingMessage>>
    fun runs(conversationId: String): Flow<List<Run>>
    fun notes(): Flow<List<Note>>

    // --- Draft Operations ---
    suspend fun draft(conversationId: String): MessageDraft?
    suspend fun saveDraft(conversationId: String, draft: MessageDraft)

    // --- Refresh Operations ---
    suspend fun refreshHome()
    suspend fun refreshConversation(conversationId: String)
    suspend fun refreshNotes()

    // --- Conversation & Messaging Operations ---
    suspend fun createConversation(characterId: String): String

    /** If supplied, consume only this draft revision in the same transaction as the outbox insert. */
    suspend fun send(conversationId: String, text: String, draftRevision: String? = null)

    suspend fun retry(messageId: String)
    suspend fun dismiss(messageId: String)
    suspend fun poll(conversationId: String)

    /** Foreground observation; cancelling collection never cancels the server's task. */
    suspend fun observeConversation(conversationId: String)
    suspend fun cancel(runId: String)
}
