package com.character.app.characters

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
    val showInspirationSheet: Boolean = false
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
        get() = liveActionText ?: currentPage.actionText

    val displayDialogueText: String
        get() = liveDialogueText ?: currentPage.dialogueText

    val hasLiveDialogue: Boolean
        get() = liveDialogueText != null

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
                liveDialogueText = null
            )
        } else {
            val nextScriptPage = (scriptPageIndex + 1) % pages.size
            copy(scriptPageIndex = nextScriptPage)
        }
    }

    fun applyAssistantReply(rawMessage: String?): HomeUiState {
        val parsed = ReplyParser.parse(rawMessage) ?: return this
        return copy(
            liveActionText = parsed.actionText,
            liveDialogueText = parsed.dialogueText,
            isAiGenerating = false
        )
    }

    fun startUserSending(): HomeUiState {
        return copy(
            inputDraft = "",
            isAiGenerating = true,
            liveActionText = "（正在沉思与审视……）",
            liveDialogueText = "..."
        )
    }

    fun finishAiGenerating(action: String, dialogue: String): HomeUiState {
        return copy(
            isAiGenerating = false,
            liveActionText = action,
            liveDialogueText = dialogue
        )
    }

    fun toggleFollow(): HomeUiState = copy(isFollowed = !isFollowed)

    fun updateDraft(text: String): HomeUiState = copy(inputDraft = text)

    fun setPersonaSheet(visible: Boolean): HomeUiState = copy(showPersonaSheet = visible)

    fun setInspirationSheet(visible: Boolean): HomeUiState = copy(showInspirationSheet = visible)
}
