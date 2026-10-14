package com.character.app.characters.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * HomeBottomBar
 *
 * Dedicated component for:
 * - Media quick actions (Photo / Video buttons)
 * - Chat input capsule (Mic button, TextField, Sparkle inspiration button, Send button)
 * Extracted from HomeScreen to enforce separation of concerns and eliminate nested indentation.
 */
@Composable
fun HomeBottomBar(
    inputDraft: String,
    onInputDraftChange: (String) -> Unit,
    isAiGenerating: Boolean,
    characterName: String,
    onOpenInspiration: () -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Media Quick Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
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

        // 2. Chat Input Bar Capsule
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

                // Sparkle AI Inspiration Button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x1FFFFFFF))
                        .clickable { onOpenInspiration() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✨", fontSize = 13.sp)
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
