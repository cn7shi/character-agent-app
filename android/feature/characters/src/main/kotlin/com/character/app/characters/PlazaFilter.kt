package com.character.app.characters

/**
 * PlazaFilter
 *
 * Domain helper for filtering plaza characters by category and search keyword.
 */
object PlazaFilter {
    fun filter(
        characters: List<PlazaCharacter>,
        category: String,
        searchQuery: String
    ): List<PlazaCharacter> {
        val trimmed = searchQuery.trim()
        return characters.filter { item ->
            val matchCategory = category == "推荐" || item.categories.contains(category)
            val matchSearch = trimmed.isBlank() ||
                    item.name.contains(trimmed, ignoreCase = true) ||
                    item.tag.contains(trimmed, ignoreCase = true) ||
                    item.subtitle.contains(trimmed, ignoreCase = true) ||
                    item.quote.contains(trimmed, ignoreCase = true)
            matchCategory && matchSearch
        }
    }
}
