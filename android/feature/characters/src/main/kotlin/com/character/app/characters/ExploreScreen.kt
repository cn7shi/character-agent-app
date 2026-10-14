package com.character.app.characters

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

@Composable
fun ExploreScreen(
    onSelectDrama: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tabs = listOf("精选", "交互短剧", "互动游戏", "声音FM", "共鸣空间")
    var selectedTab by remember { mutableStateOf("精选") }

    // State for interactive TRPG dice game
    var diceResult by remember { mutableStateOf<Int?>(null) }
    var diceFeedback by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D11))
            .testTag("explore-screen"),
        contentPadding = PaddingValues(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header with Title & Tabs (NO "(c.ai)")
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "实验玩法",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
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

                // Nav Tabs Row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tabs) { tab ->
                        val isSelected = tab == selectedTab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (isSelected) Color.White else Color(0x1AFFFFFF))
                                .border(1.dp, if (isSelected) Color.White else Color(0x14FFFFFF), RoundedCornerShape(18.dp))
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = tab,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color(0xCCFFFFFF)
                            )
                        }
                    }
                }
            }
        }

        // 2. Featured Hero Drama Banner Card (霍司爵)
        item {
            Box(
                modifier = Modifier
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

        // 3. Section: 交互短剧
        item {
            Column(
                modifier = Modifier
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

        // 4. Section: 互动游戏 (TRPG Dice & Undercover)
        item {
            Column(
                modifier = Modifier
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
                        Text("🎮", fontSize = 16.sp)
                        Text("互动游戏", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x33F5B041))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("高沉浸互动", fontSize = 10.sp, color = Color(0xFFF5B041))
                        }
                    }
                    Text("全部 >", fontSize = 12.sp, color = Color(0x80FFFFFF))
                }

                // Game Card 1: TRPG Dice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14151B))
                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x269254DE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎲", fontSize = 22.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("战术 TRPG 跑团掷骰", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x339254DE))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("D20 规则", fontSize = 9.sp, color = Color(0xFFB37FEB))
                                }
                            }
                            Text(
                                text = if (diceFeedback.isNotEmpty()) diceFeedback else "由 AI 判定潜行、说服与射击检定，掷出命运点数！",
                                fontSize = 11.sp,
                                color = if (diceFeedback.isNotEmpty()) Color(0xFFF5B041) else Color(0x80FFFFFF),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val roll = Random.nextInt(1, 21)
                            diceResult = roll
                            diceFeedback = when {
                                roll == 20 -> "大成功！(D20: 20) 突破极值，完美达成行动！"
                                roll >= 15 -> "成功！(D20: $roll) 行动顺利，触发隐藏剧情。"
                                roll >= 10 -> "普通通过。(D20: $roll) 艰难避开危险。"
                                roll == 1 -> "大失败！(D20: 1) 触发致命反转！"
                                else -> "检定失败。(D20: $roll) 局势进一步恶化！"
                            }
                            Toast.makeText(context, diceFeedback, Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x339254DE)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (diceResult != null) "D20: $diceResult" else "投骰检定",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD3ADF7)
                        )
                    }
                }

                // Game Card 2: Undercover
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14151B))
                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x2613C2C2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🕵️", fontSize = 22.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("豪门暗涌 · 谁是卧底", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0x3313C2C2))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("群聊推理", fontSize = 9.sp, color = Color(0xFF5CDBD3))
                                }
                            }
                            Text(
                                text = "多位智能体同台对线，找出潜伏在豪门中的暗线！",
                                fontSize = 11.sp,
                                color = Color(0x80FFFFFF),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            Toast.makeText(context, "正在开启「豪门暗涌」多角色群聊推理局...", Toast.LENGTH_SHORT).show()
                            onSelectDrama("huosijue")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3313C2C2)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "进入群聊",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF87E8DE)
                        )
                    }
                }
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
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
