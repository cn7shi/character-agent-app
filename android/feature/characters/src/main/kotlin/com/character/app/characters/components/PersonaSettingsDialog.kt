package com.character.app.characters.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * PersonaSettingsDialog
 *
 * Dedicated modal component for user persona settings (称呼, 性别, 我是谁).
 * Extracted from HomeScreen to enforce single responsibility and eliminate nesting.
 */
@Composable
fun PersonaSettingsDialog(
    onDismiss: () -> Unit,
    onSave: (nickname: String, gender: String, bio: String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf("女") }
    var bio by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF14151B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("‹ 返回", color = Color(0x99FFFFFF))
                    }
                    Text(
                        text = "全局设定",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.width(48.dp))
                }

                // 称呼
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("称呼", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        placeholder = { Text("智能体对我的称呼", fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFF5B041),
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 性别
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("性别", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("男", "女", "暂不透露").forEach { g ->
                            val isSelected = g == selectedGender
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFF5B041) else Color(0x1FFFFFFF))
                                    .clickable { selectedGender = g }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = g,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                // 我是谁
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("我是谁", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { bio = it },
                        placeholder = { Text("描述你所扮演角色的性格特征和事实设定，默认用于所有智能体聊天", fontSize = 12.sp) },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFF5B041),
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                }

                // 保存
                Button(
                    onClick = { onSave(nickname, selectedGender, bio) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5B041)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("保存设定", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}
