package com.character.app.characters.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * InspirationDialog
 *
 * Dedicated modal component for AI roleplay dialogue suggestions.
 * Extracted from HomeScreen to enforce single responsibility and eliminate nesting.
 */
@Composable
fun InspirationDialog(
    characterName: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val suggestions = listOf(
        "“你刚刚说的……究竟是什么意思？”",
        "（轻轻垂眸，掩饰内心的局促）“今天的事情，多谢你。”",
        "“如果我偏要留下来，你又能拿我怎样？”",
        "（向前迈近半步，直视他的眼眸）“我想知道更多关于你的过去。”",
        "“天色已经很晚了，你还不打算让我离开吗？”"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF14151B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💡 对话灵感 · $characterName",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Text("✕", color = Color(0x99FFFFFF), fontSize = 14.sp)
                    }
                }
                Text("点击即可填入对话框：", fontSize = 12.sp, color = Color(0x80FFFFFF))

                suggestions.forEach { sug ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1FFFFFFF))
                            .clickable { onSelect(sug) }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = sug,
                            fontSize = 13.sp,
                            color = Color.White,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
