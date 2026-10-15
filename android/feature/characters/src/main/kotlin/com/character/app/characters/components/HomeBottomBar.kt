package com.character.app.characters.components

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Character-aware suggestion generator for sliding inspiration chips.
 */
fun getCharacterSuggestions(characterName: String): List<String> {
    return when {
        characterName.contains("霍司爵") -> listOf(
            "“你刚刚说的……究竟是什么意思？”",
            "（直视他的冷眸）“如果我偏要留下来呢？”",
            "（轻轻垂眸，掩饰内心的局促）“今天多谢你。”",
            "“天色已经很晚了，还不打算放我离开吗？”",
            "（向前迈近半步）“我想知道更多关于你的过去。”"
        )
        characterName.contains("萧寒") -> listOf(
            "“师尊，这一剑是为何意？”",
            "（握紧剑柄）“若天下皆负你，我便逆了这天下。”",
            "“山中风雪大，先回屋歇息吧。”",
            "（垂眸行礼）“弟子谨遵教诲。”"
        )
        characterName.contains("江野") -> listOf(
            "“赛道上见真章，敢不敢比一场？”",
            "（挑眉轻笑）“你就这点能耐么？”",
            "“上车，带你兜风去。”",
            "“刚刚那个过弯很帅，教教我？”"
        )
        characterName.contains("严烈") -> listOf(
            "“长官，案发现场还有其他线索。”",
            "（立正敬礼）“保证完成任务！”",
            "“你身上有伤，先去包扎。”"
        )
        characterName.contains("沈言") -> listOf(
            "“医生，我的心跳好像有点不正常。”",
            "（递过温水）“你今天看起来很疲惫。”",
            "“不要总是把心事藏在金丝眼镜后面。”"
        )
        else -> listOf(
            "“我想知道更多关于你的过去。”",
            "（深吸一口气）“如果我拒绝呢？”",
            "“接下来你打算怎么做？”",
            "（微微一笑，保持沉默）"
        )
    }
}

/**
 * HomeBottomBar
 *
 * Dedicated component for:
 * - Media quick actions (Photo / Video buttons)
 * - Sliding Inspiration Chips (Slides up smoothly from bottom above input)
 * - Chat input capsule (Mic button, TextField, Sparkle inspiration button, Send button)
 */
@Composable
fun HomeBottomBar(
    inputDraft: String,
    onInputDraftChange: (String) -> Unit,
    isAiGenerating: Boolean,
    characterName: String,
    isInspirationVisible: Boolean,
    onToggleInspiration: () -> Unit,
    onSelectInspiration: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Media Quick Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo Button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xA61C1D24))
                    .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                    .clickable { Toast.makeText(context, "打开角色海报画廊", Toast.LENGTH_SHORT).show() },
                contentAlignment = Alignment.Center
            ) {
                Text("🖼️", fontSize = 16.sp)
            }

            // Video Button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xA61C1D24))
                    .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                    .clickable { Toast.makeText(context, "切换角色动态交互视频", Toast.LENGTH_SHORT).show() },
                contentAlignment = Alignment.Center
            ) {
                Text("🎬", fontSize = 16.sp)
            }
        }

        // 2. Sliding Inspiration Chips (Emerged from bottom above input bar)
        AnimatedVisibility(
            visible = isInspirationVisible,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + expandVertically() + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + shrinkVertically() + fadeOut()
        ) {
            val suggestions = remember(characterName) { getCharacterSuggestions(characterName) }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(suggestions) { sug ->
                    Surface(
                        color = Color(0xF21C1E26),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0x40F5B041)),
                        modifier = Modifier.clickable {
                            onSelectInspiration(sug)
                            Toast.makeText(context, "已填入灵感回复", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("✨", fontSize = 11.sp)
                            Text(
                                text = sug,
                                fontSize = 12.sp,
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 3. Chat Input Bar Capsule
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mic Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x261A1B22))
                    .clickable { Toast.makeText(context, "按住开始语音交流...", Toast.LENGTH_SHORT).show() },
                contentAlignment = Alignment.Center
            ) {
                Text("🎙️", fontSize = 20.sp)
            }

            // Input Capsule
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xC01A1B22))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (inputDraft.isEmpty()) {
                        Text(
                            text = "发消息...",
                            fontSize = 14.sp,
                            color = Color(0x73FFFFFF)
                        )
                    }
                    BasicTextField(
                        value = inputDraft,
                        onValueChange = onInputDraftChange,
                        textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Sparkle AI Inspiration Toggle Button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isInspirationVisible) Color(0xFFF5B041) else Color(0x1FFFFFFF))
                        .clickable { onToggleInspiration() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✨",
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Send Action Button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (inputDraft.isNotBlank()) Color(0xFFF5B041) else Color(0x1FFFFFFF))
                        .clickable {
                            if (inputDraft.isNotBlank() && !isAiGenerating) {
                                val textToSend = inputDraft.trim()
                                onSendMessage(textToSend)
                            } else if (inputDraft.isNotBlank() && isAiGenerating) {
                                Toast.makeText(context, "角色正在思考回复中，请稍候...", Toast.LENGTH_SHORT).show()
                            } else if (inputDraft.isBlank()) {
                                Toast.makeText(context, "请输入你想对${characterName}说的话", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (inputDraft.isNotBlank()) "➤" else "+",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (inputDraft.isNotBlank()) Color.Black else Color.White
                    )
                }
            }
        }
    }
}
