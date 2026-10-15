package com.character.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Decorator around [BackendAdapter] that applies [RetryPolicy] to idempotent read and write operations.
 *
 * Only declared idempotent business operations receive retries; raw transport POSTs do not.
 */
class ReliableBackend(
    private val delegate: BackendAdapter,
    private val policy: RetryPolicy = RetryPolicy(),
) : BackendAdapter {

    override val supportsProfile: Boolean
        get() = delegate.supportsProfile

    override suspend fun profile(): UserProfile {
        return policy.execute {
            delegate.profile()
        }
    }

    override suspend fun saveProfile(profile: UserProfile): UserProfile {
        return policy.execute(preserveAmbiguousWrite = true) {
            delegate.saveProfile(profile)
        }
    }

    override suspend fun resetProfile(version: Int): UserProfile {
        return policy.execute(preserveAmbiguousWrite = true) {
            delegate.resetProfile(version)
        }
    }

    override val supportsCharacterManagement: Boolean
        get() = delegate.supportsCharacterManagement

    override suspend fun characterDefinition(id: String): CharacterDraft {
        return policy.execute {
            delegate.characterDefinition(id)
        }
    }

    override suspend fun saveCharacter(draft: CharacterDraft): CharacterDraft {
        return policy.execute(preserveAmbiguousWrite = true) {
            delegate.saveCharacter(draft)
        }
    }

    override suspend fun deleteCharacter(id: String) {
        policy.execute {
            delegate.deleteCharacter(id)
        }
    }

    override val supportsEvents: Boolean
        get() = delegate.supportsEvents

    override fun events(runId: String, afterSequence: Long): Flow<RunEvent> {
        return delegate.events(runId, afterSequence)
    }

    override fun close() {
        delegate.close()
    }

    override suspend fun characters(cursor: String?): Page<Character> {
        return policy.execute {
            delegate.characters(cursor)
        }
    }

    override suspend fun conversations(cursor: String?): Page<Conversation> {
        return policy.execute {
            delegate.conversations(cursor)
        }
    }

    override suspend fun messages(conversationId: String, cursor: String?): Page<Message> {
        return policy.execute {
            delegate.messages(conversationId, cursor)
        }
    }

    override suspend fun notes(cursor: String?): Page<Note> {
        return policy.execute {
            delegate.notes(cursor)
        }
    }

    override suspend fun createConversation(characterId: String, requestKey: String): Conversation {
        require(requestKey.isNotBlank()) { "requestKey must not be blank" }
        return policy.execute(preserveAmbiguousWrite = true) {
            delegate.createConversation(characterId, requestKey)
        }
    }

    override suspend fun renameConversation(
        conversationId: String,
        title: String,
        expectedVersion: Int,
    ): Conversation {
        require(conversationId.isNotBlank() && title.isNotBlank() && expectedVersion > 0)
        return policy.execute(preserveAmbiguousWrite = true) {
            delegate.renameConversation(conversationId, title, expectedVersion)
        }
    }

    override suspend fun createRun(
        conversationId: String,
        messageId: String,
        requestKey: String,
        text: String,
    ): Run {
        require(requestKey.isNotBlank() && messageId.isNotBlank()) {
            "requestKey and messageId must not be blank"
        }
        return policy.execute(preserveAmbiguousWrite = true) {
            delegate.createRun(conversationId, messageId, requestKey, text)
        }
    }

    override suspend fun run(runId: String): Run {
        return policy.execute {
            delegate.run(runId)
        }
    }

    // API v1 cancel returns the existing task and never starts another execution.
    override suspend fun cancel(runId: String): Run {
        return policy.execute {
            delegate.cancel(runId)
        }
    }
}
