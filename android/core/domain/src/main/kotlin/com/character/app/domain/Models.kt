package com.character.app.domain

/**
 * Character entity.
 */
data class Character(
    val id: String,
    val name: String,
    val summary: String,
    val version: Int,
    val editable: Boolean = false,
)

/**
 * Character editing draft.
 * [version] is the expected saved version; zero denotes a new, client-assigned ID.
 */
data class CharacterDraft(
    val id: String,
    val name: String,
    val summary: String,
    val systemPrompt: String,
    val notesEnabled: Boolean,
    val version: Int,
)

/**
 * Conversation entity metadata.
 */
data class Conversation(
    val id: String,
    val characterId: String,
    val title: String,
    val updatedAt: String,
)

/**
 * Text or action block in a message.
 */
data class MessageBlock(
    val kind: String,
    val text: String,
)

/**
 * Chat message entity.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val runId: String,
    val sequence: Long,
    val role: String,
    val blocks: List<MessageBlock>,
    val status: String,
    val updatedAt: String,
) {
    val text: String
        get() = blocks.joinToString("\n") { it.text }
}

/**
 * Agent execution run state.
 */
data class Run(
    val id: String,
    val conversationId: String,
    val status: String,
    val sequence: Long,
    val input: Message,
    val output: Message,
    val simulated: Boolean,
    val error: String?,
) {
    val terminal: Boolean
        get() = status in TERMINAL_STATUSES

    private companion object {
        private val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled")
    }
}

/**
 * User saved note.
 */
data class Note(
    val id: String,
    val conversationId: String,
    val title: String,
    val content: String,
    val createdAt: String,
)

/**
 * Local outbox pending message.
 */
data class PendingMessage(
    val id: String,
    val conversationId: String,
    val text: String,
    val error: String?,
    val blocked: Boolean,
)

/**
 * Local conversation draft message.
 */
data class MessageDraft(
    val revision: String,
    val text: String,
)

enum class DraftSaveState {
    LOADING,
    SAVING,
    SAVED,
    FAILED,
}

/**
 * Generic cursor-paginated response wrapper.
 */
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/**
 * User profile entity.
 */
data class UserProfile(
    val nickname: String = "",
    val bio: String = "",
    val version: Int = 0,
)

/**
 * User profile change payload.
 * The [value] carries the expected version, including for a reset.
 */
data class ProfileChange(
    val value: UserProfile,
    val reset: Boolean = false,
)
