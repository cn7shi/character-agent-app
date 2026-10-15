package com.character.app.characters.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * RenameConversationDialog
 *
 * Clean dialog for renaming conversation titles with optimistic validation.
 */
@Composable
fun RenameConversationDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    val isValid = title.trim().isNotBlank() && title.trim().length <= 100

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF14151B),
            border = BorderStroke(1.dp, Color(0x26FFFFFF)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "修改会话名称",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 100) title = it },
                    placeholder = { Text("请输入会话名称（1-100 字）", color = Color(0x66FFFFFF), fontSize = 14.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFF5B041),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        cursorColor = Color(0xFFF5B041),
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color(0x99FFFFFF))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isValid) {
                                onConfirm(title.trim())
                                onDismiss()
                            }
                        },
                        enabled = isValid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF5B041),
                            contentColor = Color.Black,
                            disabledContainerColor = Color(0x33F5B041),
                            disabledContentColor = Color(0x40000000)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
