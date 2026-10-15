package com.character.app.characters

import java.util.UUID

/**
 * HomeChatMessage
 *
 * Represents an individual message turn in the WeChat-style roleplay conversation stream.
 */
data class HomeChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" or "assistant"
    val actionText: String? = null,
    val dialogueText: String,
    val voiceDuration: Int = 5,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * HomeUiState
 *
 * Immutable UI State model for HomeScreen, following strict Clean Architecture & MVVM principles.
 * Contains pure state data and deterministic transition functions (reducers).
 */
data class HomeUiState(
    val characterIndex: Int = 0,
    val scriptPageIndex: Int = 0,
    val liveActionText: String? = null,
    val liveDialogueText: String? = null,
    val isAiGenerating: Boolean = false,
    val isFollowed: Boolean = false,
    val inputDraft: String = "",
    val showPersonaSheet: Boolean = false,
    val showInspirationSheet: Boolean = false,
    val characterMessages: Map<String, List<HomeChatMessage>> = emptyMap()
) {
    val currentChar: PlazaCharacter
        get() = PLAZA_CHARACTERS[characterIndex.coerceIn(0, PLAZA_CHARACTERS.lastIndex)]

    val pages: List<ScriptPage>
        get() = currentChar.pages.ifEmpty {
            listOf(ScriptPage(1, 5, "(${currentChar.quote})", currentChar.quote))
        }

    val currentPage: ScriptPage
        get() = pages[scriptPageIndex.coerceIn(0, pages.lastIndex)]

    val displayActionText: String
        get() = liveActionText ?: currentMessages.lastOrNull { it.role == "assistant" }?.actionText ?: currentPage.actionText

    val displayDialogueText: String
        get() = liveDialogueText ?: currentMessages.lastOrNull { it.role == "assistant" }?.dialogueText ?: currentPage.dialogueText

    val hasLiveDialogue: Boolean
        get() = liveDialogueText != null || characterMessages[currentChar.id]?.any { it.role == "user" } == true

    /**
     * Active message stream for the current character in WeChat-style sequential order.
     */
    val currentMessages: List<HomeChatMessage>
        get() {
            val list = characterMessages[currentChar.id]
            return if (!list.isNullOrEmpty()) {
                list
            } else {
                listOf(
                    HomeChatMessage(
                        id = "prologue-${currentChar.id}-${scriptPageIndex}",
                        role = "assistant",
                        actionText = currentPage.actionText,
                        dialogueText = currentPage.dialogueText,
                        voiceDuration = currentPage.voiceDuration
                    )
                )
            }
        }

    fun selectCharacterById(charId: String?): HomeUiState {
        if (charId == null) return this
        val found = PLAZA_CHARACTERS.indexOfFirst { it.id == charId }
        return if (found >= 0 && found != characterIndex) {
            copy(
                characterIndex = found,
                scriptPageIndex = 0,
                liveActionText = null,
                liveDialogueText = null,
                isAiGenerating = false,
                isFollowed = false
            )
        } else this
    }

    fun nextCharacter(): HomeUiState {
        val nextIdx = (characterIndex + 1) % PLAZA_CHARACTERS.size
        return copy(
            characterIndex = nextIdx,
            scriptPageIndex = 0,
            liveActionText = null,
            liveDialogueText = null,
            isAiGenerating = false,
            isFollowed = false
        )
    }

    fun previousCharacter(): HomeUiState {
        val prevIdx = (characterIndex - 1 + PLAZA_CHARACTERS.size) % PLAZA_CHARACTERS.size
        return copy(
            characterIndex = prevIdx,
            scriptPageIndex = 0,
            liveActionText = null,
            liveDialogueText = null,
            isAiGenerating = false,
            isFollowed = false
        )
    }

    fun advanceScript(): HomeUiState {
        return if (hasLiveDialogue) {
            // Dismiss live dialogue and return to script progression
            copy(
                liveActionText = null,
                liveDialogueText = null,
                characterMessages = characterMessages - currentChar.id
            )
        } else {
            val nextScriptPage = (scriptPageIndex + 1) % pages.size
            copy(scriptPageIndex = nextScriptPage)
        }
    }

    fun addUserMessage(userText: String): HomeUiState {
        val userMsg = HomeChatMessage(
            role = "user",
            dialogueText = userText
        )
        val existing = currentMessages
        val updated = existing + userMsg
        return copy(
            characterMessages = characterMessages + (currentChar.id to updated),
            inputDraft = "",
            isAiGenerating = true,
            liveActionText = "（正在沉思与审视……）",
            liveDialogueText = "...",
            showInspirationSheet = false
        )
    }

    fun addAssistantReply(action: String, dialogue: String): HomeUiState {
        val existing = currentMessages.toMutableList()
        val lastIndex = existing.lastIndex
        val hasUserMsgBefore = existing.any { it.role == "user" }

        if (lastIndex >= 0 && existing[lastIndex].role == "assistant" && hasUserMsgBefore) {
            val lastMsg = existing[lastIndex]
            existing[lastIndex] = lastMsg.copy(
                actionText = action,
                dialogueText = dialogue
            )
        } else {
            existing.add(
                HomeChatMessage(
                    role = "assistant",
                    actionText = action,
                    dialogueText = dialogue,
                    voiceDuration = 5
                )
            )
        }
        return copy(
            characterMessages = characterMessages + (currentChar.id to existing),
            liveActionText = action,
            liveDialogueText = dialogue,
            isAiGenerating = false,
            showInspirationSheet = true
        )
    }

    fun applyAssistantReply(rawMessage: String?): HomeUiState {
        val parsed = ReplyParser.parse(rawMessage) ?: return copy(isAiGenerating = false)
        return addAssistantReply(parsed.actionText, parsed.dialogueText)
    }

    fun startUserSending(): HomeUiState {
        return copy(
            inputDraft = "",
            isAiGenerating = true,
            liveActionText = "（正在沉思与审视……）",
            liveDialogueText = "...",
            showInspirationSheet = false
        )
    }

    fun finishAiGenerating(action: String, dialogue: String): HomeUiState {
        return addAssistantReply(action, dialogue)
    }

    fun toggleFollow(): HomeUiState = copy(isFollowed = !isFollowed)

    fun updateDraft(text: String): HomeUiState = copy(inputDraft = text)

    fun setPersonaSheet(visible: Boolean): HomeUiState = copy(showPersonaSheet = visible)

    fun setInspirationSheet(visible: Boolean): HomeUiState = copy(showInspirationSheet = visible)
}
