package com.character.app.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.character.app.domain.PendingMessage

/**
 * PendingMessageCard
 *
 * Card component displaying an in-flight, failed, or blocked pending message with retry/dismiss actions.
 */
@Composable
fun PendingMessageCard(
    pendingItem: PendingMessage,
    busy: Boolean,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(pendingItem.text)
            Text(
                text = pendingItem.error ?: "正在提交…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pendingItem.blocked) {
                TextButton(
                    onClick = { onDismiss(pendingItem.id) },
                    enabled = !busy
                ) {
                    Text("移除未受理消息")
                }
            } else {
                TextButton(
                    onClick = { onRetry(pendingItem.id) },
                    enabled = !busy
                ) {
                    Text("用原请求重试")
                }
            }
        }
    }
}
