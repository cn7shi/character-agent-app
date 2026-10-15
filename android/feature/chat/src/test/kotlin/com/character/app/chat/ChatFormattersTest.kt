package com.character.app.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ChatFormattersTest {

    @Test
    fun statusLabelTranslatesProperly() {
        assertEquals("等待回复", ChatFormatters.statusLabel("pending"))
        assertEquals("正在回复", ChatFormatters.statusLabel("streaming"))
        assertEquals("正在停止", ChatFormatters.statusLabel("cancel_requested"))
        assertEquals("已停止", ChatFormatters.statusLabel("cancelled"))
        assertEquals("未完成", ChatFormatters.statusLabel("failed"))
        assertEquals("已完成", ChatFormatters.statusLabel("completed"))
    }

    @Test
    fun defaultGreetingReturnsCustomizedGreetingForLinzhou() {
        val greeting = ChatFormatters.defaultGreeting("林舟", "")
        assertTrue(greeting.contains("林舟") || greeting.contains("登山包"))
    }

    @Test
    fun generateInspirationsHandlesQuestionMark() {
        val ideas = ChatFormatters.generateInspirations("今天你想去哪里呢？", "测试角色")
        assertEquals(3, ideas.size)
        assertTrue(ideas.any { it.contains("为什么会这么问") || it.contains("挺好的") })
    }

    @Test
    fun buildRoleplayAnnotatedStringHandlesAsterisks() {
        val raw = "*微微一笑* 很高兴见到你。"
        val annotated = ChatFormatters.buildRoleplayAnnotatedString(raw, false, Color.Gray, Color.White)
        assertEquals(raw, annotated.text)
    }
}
