package com.character.app.characters.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ExploreGamesSection
 *
 * Interactive roleplay mini-games section including TRPG D20 check and Undercover multiplayer drama.
 */
@Composable
fun ExploreGamesSection(
    diceResult: Int?,
    diceFeedback: String,
    onRollDice: () -> Unit,
    onSelectUndercover: () -> Unit,
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
                        text = diceFeedback.ifEmpty { "由 AI 判定潜行、说服与射击检定，掷出命运点数！" },
                        fontSize = 11.sp,
                        color = if (diceFeedback.isNotEmpty()) Color(0xFFF5B041) else Color(0x80FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onRollDice,
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
                onClick = onSelectUndercover,
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
