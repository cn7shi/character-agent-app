package com.character.app.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.character.app.characters.components.PlazaBannerCarousel
import com.character.app.characters.components.PlazaCategoryChips
import com.character.app.characters.components.PlazaCharacterCard
import com.character.app.characters.components.PlazaHeader

/**
 * PlazaScreen
 *
 * Discovery Plaza feed with search, category filtering, banner swiper, and 2-column character grid.
 * Refactored into a lean orchestrator (~80 lines) composed of atomic components.
 */
@Composable
fun PlazaScreen(
    onSelectCharacter: (PlazaCharacter) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("推荐") }

    val bannerItems = remember { PLAZA_CHARACTERS.filter { it.isBanner } }

    val filteredCharacters = remember(selectedCategory, searchQuery) {
        PlazaFilter.filter(PLAZA_CHARACTERS, selectedCategory, searchQuery)
    }
    val chunkedCharacters = remember(filteredCharacters) { filteredCharacters.chunked(2) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D11))
            .testTag("plaza-screen"),
        contentPadding = PaddingValues(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Plaza Sticky Header (Nav Title + Settings + Search Capsule)
        item {
            PlazaHeader(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onOpenSettings = onOpenSettings
            )
        }

        // 2. Horizontal Category Filter Chips
        item {
            PlazaCategoryChips(
                categories = PLAZA_CATEGORIES,
                selectedCategory = selectedCategory,
                onSelectCategory = { selectedCategory = it }
            )
        }

        // 3. Featured Banner Carousel
        if (bannerItems.isNotEmpty()) {
            item {
                PlazaBannerCarousel(
                    bannerItems = bannerItems,
                    onSelectCharacter = onSelectCharacter
                )
            }
        }

        // 4. 2-Column Character Grid Feed
        items(chunkedCharacters) { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (charItem in rowItems) {
                    Box(modifier = Modifier.weight(1f)) {
                        PlazaCharacterCard(
                            character = charItem,
                            onClick = { onSelectCharacter(charItem) }
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
