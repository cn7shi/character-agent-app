package com.character.app.characters

/**
 * ReplyParser
 *
 * Extracts leading bracketed action narration and preserves full sequential roleplay dialogue.
 * Supports ASCII parentheses (), full-width Chinese parentheses （）, and asterisks **.
 */
object ReplyParser {
    data class ParsedReply(
        val actionText: String,
        val dialogueText: String
    )

    fun parse(rawText: String?, defaultAction: String = "（若有所思地看着你）"): ParsedReply? {
        if (rawText.isNullOrBlank()) return null
        val normalized = rawText.replace(Regex("""\n{3,}"""), "\n\n").trim()
        val paragraphs = normalized.split(Regex("""\n{2,}""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return null

        val p0 = paragraphs[0]
        val action: String
        val dialogue: String

        if (p0.startsWith("（") || p0.startsWith("(") || p0.startsWith("*")) {
            val openChar = p0[0]
            val closeChar = when (openChar) {
                '（' -> '）'
                '(' -> ')'
                '*' -> '*'
                else -> '）'
            }

            if (p0.endsWith(closeChar)) {
                // First paragraph is entirely an action narration
                action = normalizeAction(p0)
                dialogue = if (paragraphs.size > 1) {
                    paragraphs.drop(1).joinToString("\n\n")
                } else {
                    p0.trim('（', '）', '(', ')', '*').trim()
                }
            } else {
                // First paragraph contains action followed by inline dialogue
                val closeIdx = p0.indexOf(closeChar, startIndex = 1)
                if (closeIdx != -1) {
                    action = normalizeAction(p0.substring(0, closeIdx + 1))
                    val rest = p0.substring(closeIdx + 1).trim()
                    val restList = if (rest.isNotEmpty()) listOf(rest) + paragraphs.drop(1) else paragraphs.drop(1)
                    dialogue = if (restList.isNotEmpty()) restList.joinToString("\n\n") else p0
                } else {
                    action = defaultAction
                    dialogue = normalized
                }
            }
        } else {
            action = defaultAction
            dialogue = normalized
        }

        return ParsedReply(
            actionText = action,
            dialogueText = dialogue
        )
    }

    private fun normalizeAction(raw: String): String {
        val inner = raw.trim().trim('（', '）', '(', ')', '*').trim()
        return "（$inner）"
    }
}
