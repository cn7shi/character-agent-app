package com.character.app.characters

import org.junit.Assert.*
import org.junit.Test

class HomeUiStateTest {

    @Test
    fun replyParserExtractsParenActionsAndDialogue() {
        val raw = "（眼神微冷，俯身注视着你）别以为这样就能逃过去。"
        val parsed = ReplyParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("（眼神微冷，俯身注视着你）", parsed?.actionText)
        assertEquals("别以为这样就能逃过去。", parsed?.dialogueText)
    }

    @Test
    fun replyParserHandlesFullWidthAndHalfWidthBrackets() {
        val rawHalf = "(轻笑一声) 你倒是挺有意思。"
        val parsedHalf = ReplyParser.parse(rawHalf)
        assertNotNull(parsedHalf)
        assertEquals("（轻笑一声）", parsedHalf?.actionText)
        assertEquals("你倒是挺有意思。", parsedHalf?.dialogueText)

        val rawAsterisk = "*微微蹙眉* 谁允许你进来的？"
        val parsedAsterisk = ReplyParser.parse(rawAsterisk)
        assertNotNull(parsedAsterisk)
        assertEquals("（微微蹙眉）", parsedAsterisk?.actionText)
        assertEquals("谁允许你进来的？", parsedAsterisk?.dialogueText)
    }

    @Test
    fun replyParserHandlesDialogueOnlyWithoutActions() {
        val raw = "你今天过得如何？"
        val parsed = ReplyParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("（若有所思地看着你）", parsed?.actionText)
        assertEquals("你今天过得如何？", parsed?.dialogueText)
    }

    @Test
    fun replyParserReturnsNullForEmptyInput() {
        assertNull(ReplyParser.parse(null))
        assertNull(ReplyParser.parse(""))
        assertNull(ReplyParser.parse("   "))
    }

    @Test
    fun initialHomeUiStateDefaultsToFirstCharacter() {
        val state = HomeUiState()
        assertEquals(0, state.characterIndex)
        assertEquals(PLAZA_CHARACTERS[0].name, state.currentChar.name)
        assertEquals(0, state.scriptPageIndex)
        assertNull(state.liveActionText)
        assertNull(state.liveDialogueText)
        assertFalse(state.isAiGenerating)
        assertFalse(state.isFollowed)
    }

    @Test
    fun characterTransitionsCycleProperly() {
        val state = HomeUiState(characterIndex = 0)
        val next = state.nextCharacter()
        assertEquals(1, next.characterIndex)
        assertEquals(0, next.scriptPageIndex)

        val wrapped = HomeUiState(characterIndex = PLAZA_CHARACTERS.size - 1).nextCharacter()
        assertEquals(0, wrapped.characterIndex)

        val prev = HomeUiState(characterIndex = 0).previousCharacter()
        assertEquals(PLAZA_CHARACTERS.size - 1, prev.characterIndex)
    }

    @Test
    fun scriptProgressionAdvancesPagesAndDismissesLiveDialogue() {
        val state = HomeUiState(characterIndex = 0, scriptPageIndex = 0)
        val advanced = state.advanceScript()
        assertEquals(1, advanced.scriptPageIndex)

        val stateWithLive = advanced.copy(liveActionText = "动作", liveDialogueText = "对话")
        val dismissed = stateWithLive.advanceScript()
        // Advancing when live dialogue is active should dismiss live dialogue and keep the script page
        assertNull(dismissed.liveActionText)
        assertNull(dismissed.liveDialogueText)
        assertEquals(1, dismissed.scriptPageIndex)
    }

    @Test
    fun liveReplyParsingUpdatesStateDirectly() {
        val state = HomeUiState().startUserSending()
        assertTrue(state.isAiGenerating)
        assertEquals("...", state.displayDialogueText)

        val updated = state.applyAssistantReply("（低笑）你总算明白了。")
        assertFalse(updated.isAiGenerating)
        assertEquals("（低笑）", updated.displayActionText)
        assertEquals("你总算明白了。", updated.displayDialogueText)
    }

    @Test
    fun followAndDialogTogglesWorkPredictably() {
        val state = HomeUiState()
        assertFalse(state.isFollowed)
        val followed = state.toggleFollow()
        assertTrue(followed.isFollowed)
        val unfollowed = followed.toggleFollow()
        assertFalse(unfollowed.isFollowed)

        val withPersona = state.setPersonaSheet(true)
        assertTrue(withPersona.showPersonaSheet)

        val withInspiration = state.setInspirationSheet(true)
        assertTrue(withInspiration.showInspirationSheet)
    }
}
