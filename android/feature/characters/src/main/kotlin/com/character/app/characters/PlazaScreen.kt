package com.character.app.characters

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun PlazaScreen(
    onSelectCharacter: (PlazaCharacter) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("推荐") }

    val bannerItems = remember { PLAZA_CHARACTERS.filter { it.isBanner } }
    val pagerState = rememberPagerState(pageCount = { bannerItems.size })

    // Auto-scroll banner
    LaunchedEffect(pagerState) {
        while (true) {
            delay(4000)
            if (bannerItems.isNotEmpty()) {
                val next = (pagerState.currentPage + 1) % bannerItems.size
                pagerState.animateScrollToPage(next, animationSpec = tween(600))
            }
        }
    }

    // Filter characters
    val filteredCharacters = remember(selectedCategory, searchQuery) {
        PLAZA_CHARACTERS.filter { item ->
            val matchCategory = selectedCategory == "推荐" || item.categories.contains(selectedCategory)
            val matchSearch = searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.tag.contains(searchQuery, ignoreCase = true) ||
                    item.subtitle.contains(searchQuery, ignoreCase = true) ||
                    item.quote.contains(searchQuery, ignoreCase = true)
            matchCategory && matchSearch
        }
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
        // 1. Plaza Sticky Header (Universal Top Bar + Search + Category Filter Chips)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Universal Nav Bar: Safe Zone + [Search] [Settings] (NO "C.ai")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "探索广场",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0x14FFFFFF))
                                .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                                .clickable { onOpenSettings() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚙️", fontSize = 15.sp)
                        }
                    }
                }

                // Search Capsule Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xC01A1B22))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(22.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索感兴趣的内容",
                                fontSize = 13.5.sp,
                                color = Color(0x73FFFFFF)
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = TextStyle(fontSize = 13.5.sp, color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "✕",
                            fontSize = 14.sp,
                            color = Color(0x80FFFFFF),
                            modifier = Modifier.clickable { searchQuery = "" }
                        )
                    }
                }
            }
        }

        // 2. Horizontal Scrollable Category Chips (Active is pure white pill with black text)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(PLAZA_CATEGORIES) { cat ->
                    val isSelected = cat == selectedCategory
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isSelected) Color.White else Color(0x1AFFFFFF))
                            .border(
                                1.dp,
                                if (isSelected) Color.White else Color(0x14FFFFFF),
                                RoundedCornerShape(18.dp)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = cat,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.Black else Color(0xCCFFFFFF)
                        )
                    }
                }
            }
        }

        // 3. Featured Banner Carousel (Swiper - 185dp height with real artwork)
        if (bannerItems.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(185.dp)
                            .clip(RoundedCornerShape(18.dp))
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val item = bannerItems[page]
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { onSelectCharacter(item) }
                            ) {
                                Image(
                                    painter = painterResource(item.bgResId),
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                // Gradient overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0x33000000),
                                                    Color(0x80050507),
                                                    Color(0xF207080B)
                                                )
                                            )
                                        )
                                )
                                // Content
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Top tag
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0x66000000))
                                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = item.bannerTag,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(item.badgeColorHex)
                                        )
                                    }

                                    // Bottom info
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = item.bannerTitle,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = item.bannerDesc,
                                            fontSize = 11.5.sp,
                                            color = Color(0xB3FFFFFF),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color(item.badgeColorHex))
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "进入对话 >",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black
                                                )
                                            }
                                            Text(
                                                text = item.author,
                                                fontSize = 11.sp,
                                                color = Color(0x80FFFFFF)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Dots pagination
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(bannerItems.size) { idx ->
                                val active = pagerState.currentPage == idx
                                Box(
                                    modifier = Modifier
                                        .size(if (active) 6.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(if (active) Color.White else Color(0x4DFFFFFF))
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. 2-Column Character Grid Feed (195dp cover height with real artwork)
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

@Composable
fun PlazaCharacterCard(
    character: PlazaCharacter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14151B))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        // Cover Image (195dp height matching H5)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(195.dp)
        ) {
            Image(
                painter = painterResource(character.bgResId),
                contentDescription = character.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Bottom gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1.0f to Color(0xF214151B)
                        )
                    )
            )
            // Top-left category tag
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
            ) {
                Text(
                    text = character.badgeCategory,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(character.badgeColorHex)
                )
            }
            // Top-right vertical "由AI生成" badge
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x80000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "由AI生成",
                    fontSize = 9.sp,
                    color = Color(0xB3FFFFFF)
                )
            }
        }

        // Card Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Name row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = character.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "· ${character.subtitle}",
                    fontSize = 11.5.sp,
                    color = Color(0x99FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = character.gender,
                    fontSize = 11.sp,
                    color = if (character.gender == "♂") Color(0xFF69C0FF) else Color(0xFFFF85C0)
                )
            }

            // Quote
            Text(
                text = character.quote,
                fontSize = 11.5.sp,
                color = Color(0x80FFFFFF),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )

            Spacer(Modifier.height(2.dp))

            // Footer: views + author
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("👁️", fontSize = 9.sp)
                    Text(
                        text = character.likes,
                        fontSize = 10.sp,
                        color = Color(0x80FFFFFF)
                    )
                }
                Text(
                    text = character.author,
                    fontSize = 10.sp,
                    color = Color(0x66FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
