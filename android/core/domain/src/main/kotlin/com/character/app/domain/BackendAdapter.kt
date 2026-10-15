package com.character.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * The backend protocol can change without changing repository or presentation contracts.
 */
interface BackendGateway {
    suspend fun profile(): UserProfile = throw BackendFailure(FailureKind.UNSUPPORTED)
    suspend fun saveProfile(profile: UserProfile): UserProfile = throw BackendFailure(FailureKind.UNSUPPORTED)
    suspend fun resetProfile(version: Int): UserProfile = throw BackendFailure(FailureKind.UNSUPPORTED)
    suspend fun characterDefinition(id: String): CharacterDraft = throw BackendFailure(FailureKind.UNSUPPORTED)
    suspend fun saveCharacter(draft: CharacterDraft): CharacterDraft = throw BackendFailure(FailureKind.UNSUPPORTED)
    suspend fun deleteCharacter(id: String): Unit = throw BackendFailure(FailureKind.UNSUPPORTED)

    suspend fun characters(cursor: String?): Page<Character>
    suspend fun conversations(cursor: String?): Page<Conversation>
    suspend fun messages(conversationId: String, cursor: String?): Page<Message>
    suspend fun notes(cursor: String?): Page<Note>

    suspend fun createConversation(characterId: String, requestKey: String): Conversation
    suspend fun renameConversation(conversationId: String, title: String, expectedVersion: Int): Conversation =
        throw BackendFailure(FailureKind.UNSUPPORTED)
    suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String): Run
    suspend fun run(runId: String): Run
    suspend fun cancel(runId: String): Run
}

/**
 * Protocol-neutral boundary. Unsupported capabilities must be declared, not silently emulated.
 */
interface BackendAdapter : BackendGateway, AutoCloseable {
    val supportsProfile: Boolean get() = false
    val supportsCharacterManagement: Boolean get() = false
    val supportsEvents: Boolean
    fun events(runId: String, afterSequence: Long): Flow<RunEvent>
}

/**
 * Server-sent event during execution of an agent run.
 */
data class RunEvent(
    val runId: String,
    val sequence: Long,
    val kind: EventKind,
    val message: Message? = null,
    val error: String? = null,
)

enum class EventKind {
    ACCEPTED,
    STARTED,
    MESSAGE,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TOOL,
    UNKNOWN,
}
