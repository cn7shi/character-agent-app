package com.character.app.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle

/**
 * ChatFormatters
 *
 * Formatting and roleplay text helpers for ChatScreen.
 */
object ChatFormatters {

    fun statusLabel(status: String): String = when (status) {
        "pending", "queued" -> "等待回复"
        "running", "streaming" -> "正在回复"
        "cancel_requested" -> "正在停止"
        "cancelled" -> "已停止"
        "failed" -> "未完成"
        else -> "已完成"
    }

    fun defaultGreeting(name: String, summary: String): String {
        return when {
            name.contains("林舟") -> "*背着登山包站在窗边看地图，转过头对你温和地笑了笑*\n你好呀！我正在规划下一趟旅程呢，今天想和我聊些什么？"
            name.contains("星禾") -> "*合上手中的笔记本，托着下巴好奇地望着你*\n嘿，你来了！今天身边有没有发生什么有趣或特别的事情想跟我分享？"
            summary.isNotBlank() -> "*向你微微颔首致意*\n你好！我是$name。$summary\n今天想聊些什么？"
            name.isNotBlank() -> "*微笑着看向你*\n你好呀，我是$name，很高兴见到你！今天想聊聊什么？"
            else -> "试着说“你好”或“今天有点累”。\n林舟的新会话也可以发送：\n记笔记：周末安排；买咖啡；看电影"
        }
    }

    /** Parses *action/narration* text into italic styling for immersive roleplay */
    fun buildRoleplayAnnotatedString(
        text: String,
        isUser: Boolean,
        italicColor: Color,
        normalColor: Color
    ): AnnotatedString {
        val builder = AnnotatedString.Builder()
        val regex = Regex("""(\*[^*]+\*|（[^）]+）|\([^\)]+\))""")
        var lastIndex = 0
        for (match in regex.findAll(text)) {
            if (match.range.first > lastIndex) {
                val normal = text.substring(lastIndex, match.range.first)
                builder.pushStyle(SpanStyle(color = normalColor, fontStyle = FontStyle.Normal))
                builder.append(normal)
                builder.pop()
            }
            val actionText = match.value
            builder.pushStyle(SpanStyle(color = italicColor, fontStyle = FontStyle.Italic))
            builder.append(actionText)
            builder.pop()
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            val normal = text.substring(lastIndex)
            builder.pushStyle(SpanStyle(color = normalColor, fontStyle = FontStyle.Normal))
            builder.append(normal)
            builder.pop()
        }
        return builder.toAnnotatedString()
    }

    /** Derives 3 lightweight roleplay inspiration responses from assistant text without hitting external LLM */
    fun generateInspirations(lastText: String, characterName: String): List<String> {
        val clean = lastText.replace(Regex("""\*.*?\*|（.*?）|\(.*?\)|[\r\n]"""), " ").trim()
        return when {
            clean.contains("？") || clean.contains("?") -> {
                listOf(
                    "*认真地想了想* 我觉得挺好的，你觉得呢？",
                    "*好奇地看着你* 为什么会这么问呢？我想听听你的看法。",
                    "*微笑着摇摇头* 好像确实有点难选，要不你给我点建议？"
                )
            }
            clean.contains("旅行") || clean.contains("旅程") || clean.contains("走") -> {
                listOf(
                    "*靠在窗边* 听起来很有趣，你最推荐哪个地方？",
                    "*眼睛一亮* 真的很想去看看！要不我们一起做个攻略？",
                    "*托着下巴看着你，示意你继续说下去*"
                )
            }
            clean.contains("故事") || clean.contains("日常") || clean.contains("生活") -> {
                listOf(
                    "*安静地托着腮* 后来呢？事情是怎么发展的？",
                    "*若有所思地点点头* 确实很有感触，换作是我可能也会这样想。",
                    "*托着下巴看着你，示意你继续说下去*"
                )
            }
            clean.isNotBlank() -> {
                listOf(
                    "*饶有兴致地注视着你* 真的吗？快仔细同我说说。",
                    "*若有所思地点头* 原来是这样，如果换个角度看呢？",
                    "*托着下巴看着你，示意你继续说下去*"
                )
            }
            else -> {
                listOf(
                    "*微笑着望向你* 很高兴见到你，今天过得开心吗？",
                    "*托着下巴* 我刚刚还在想，今天会聊些什么呢。",
                    "*向你招了招手* 快坐过来，跟我讲讲你的近况吧！"
                )
            }
        }
    }
}

// Preserve backward-compatible package level top functions for external callers
fun defaultGreeting(name: String, summary: String): String =
    ChatFormatters.defaultGreeting(name, summary)

fun buildRoleplayAnnotatedString(text: String, isUser: Boolean, italicColor: Color, normalColor: Color): AnnotatedString =
    ChatFormatters.buildRoleplayAnnotatedString(text, isUser, italicColor, normalColor)

internal fun generateInspirations(lastText: String, characterName: String): List<String> =
    ChatFormatters.generateInspirations(lastText, characterName)
