package com.character.app.characters.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.characters.PlazaCharacter
import kotlinx.coroutines.delay

/**
 * PlazaBannerCarousel
 *
 * Auto-scrolling featured character banner carousel with pagination dots.
 */
@Composable
fun PlazaBannerCarousel(
    bannerItems: List<PlazaCharacter>,
    onSelectCharacter: (PlazaCharacter) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bannerItems.isEmpty()) return

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

    Column(
        modifier = modifier
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
