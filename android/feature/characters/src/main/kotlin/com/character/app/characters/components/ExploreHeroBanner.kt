package com.character.app.characters.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.characters.R

/**
 * ExploreHeroBanner
 *
 * Featured hero drama card component for ExploreScreen.
 */
@Composable
fun ExploreHeroBanner(
    onSelectDrama: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(200.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onSelectDrama("huosijue") }
    ) {
        Image(
            painter = painterResource(R.drawable.character_bg),
            contentDescription = "今日主推短剧",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE53935))
                            .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    ) {
                        Text("🔥 今日主推短剧", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x66000000))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    ) {
                        Text("沉浸多分支", fontSize = 10.sp, color = Color(0xCCFFFFFF))
                    }
                }
                Text("12.8万人在玩", fontSize = 10.5.sp, color = Color(0x99FFFFFF))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("《暗夜权杖：绝密博弈》", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(3.dp))
                    Text("与霍氏掌权者并肩交锋，你的每个抉择都改写终局", fontSize = 11.5.sp, color = Color(0xB3FFFFFF), maxLines = 1)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFF5B041))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("▶ 立即体验", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}
