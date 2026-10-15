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
 * ExploreDramaSection
 *
 * Interactive short drama section with title bar and drama cards.
 */
@Composable
fun ExploreDramaSection(
    onSelectDrama: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("🎬", fontSize = 16.sp)
                Text("交互短剧", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33E53935))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("热门剧场", fontSize = 10.sp, color = Color(0xFFFF7875))
                }
            }
            Text("全部 >", fontSize = 12.sp, color = Color(0x80FFFFFF))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Drama Card 1: 严烈
            Box(modifier = Modifier.weight(1f)) {
                DramaCard(
                    title = "《假面危机：午夜枪声》",
                    desc = "顶级名流宴会下的绝密搜捕，暗夜交错的致命心跳。",
                    tag = "严烈 主演",
                    tagColor = Color(0xFFFA8C16),
                    playCount = "4.8万人在玩",
                    imageRes = R.drawable.char_gangster,
                    onClick = { onSelectDrama("yanlie") }
                )
            }

            // Drama Card 2: 萧寒
            Box(modifier = Modifier.weight(1f)) {
                DramaCard(
                    title = "《剑动九霄：霜雪问道》",
                    desc = "寒夜暴风雪中古刹剑鸣，孤高剑尊为你劈开生路。",
                    tag = "萧寒 独家",
                    tagColor = Color(0xFF4ECDC4),
                    playCount = "6.3万人在玩",
                    imageRes = R.drawable.char_wuxia,
                    onClick = { onSelectDrama("xiaohan") }
                )
            }
        }
    }
}

@Composable
fun DramaCard(
    title: String,
    desc: String,
    tag: String,
    tagColor: Color,
    playCount: String,
    imageRes: Int,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xE614151B))
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(tag, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = tagColor)
            }
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x80000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(playCount, fontSize = 9.sp, color = Color(0xB3FFFFFF))
            }
        }
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text(desc, fontSize = 11.sp, color = Color(0x80FFFFFF), maxLines = 2, lineHeight = 15.sp)
        }
    }
}
