package com.character.app.characters

/**
 * ReplyParser
 *
 * Extracts bracketed stage directions/actions and spoken dialogue from assistant responses.
 * Supports ASCII parentheses (), full-width Chinese parentheses （）, and asterisks **.
 */
object ReplyParser {
    private val PAREN_REGEX = Regex("""[\(（\*](.*?)[\)）\*]""")

    data class ParsedReply(
        val actionText: String,
        val dialogueText: String
    )

    fun parse(rawText: String?, defaultAction: String = "（若有所思地看着你）"): ParsedReply? {
        if (rawText.isNullOrBlank()) return null
        val match = PAREN_REGEX.find(rawText)
        val action = match?.groupValues?.get(1)?.let { "（$it）" } ?: defaultAction
        val dialogue = PAREN_REGEX.replace(rawText, "").trim().ifEmpty { rawText.trim() }
        return ParsedReply(
            actionText = action,
            dialogueText = dialogue
        )
    }
}
