package com.character.app.characters

import org.junit.Assert.*
import org.junit.Test

class PlazaFilterTest {

    @Test
    fun returnsAllCharactersWhenRecommendCategoryAndEmptySearch() {
        val result = PlazaFilter.filter(PLAZA_CHARACTERS, "推荐", "")
        assertEquals(PLAZA_CHARACTERS.size, result.size)
    }

    @Test
    fun filtersCharactersByCategory() {
        val modern = PlazaFilter.filter(PLAZA_CHARACTERS, "都市", "")
        assertTrue(modern.isNotEmpty())
        assertTrue(modern.all { it.categories.contains("都市") })
    }

    @Test
    fun filtersCharactersByKeywordInName() {
        val found = PlazaFilter.filter(PLAZA_CHARACTERS, "推荐", "霍司爵")
        assertEquals(1, found.size)
        assertEquals("霍司爵", found.first().name)
    }

    @Test
    fun filtersCharactersByKeywordInQuoteOrSubtitle() {
        val found = PlazaFilter.filter(PLAZA_CHARACTERS, "推荐", "剑尊")
        assertTrue(found.any { it.name == "萧寒" })
    }

    @Test
    fun handlesWhitespaceAndCaseInsensitivity() {
        val found = PlazaFilter.filter(PLAZA_CHARACTERS, "推荐", "   YANLIE  ")
        assertEquals(0, found.size) // Name is in Chinese, but let's test tag/quote
        val foundChinese = PlazaFilter.filter(PLAZA_CHARACTERS, "推荐", "   严烈  ")
        assertEquals(1, foundChinese.size)
        assertEquals("严烈", foundChinese.first().name)
    }
}
