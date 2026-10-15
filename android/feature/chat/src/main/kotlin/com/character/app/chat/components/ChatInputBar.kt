package com.character.app.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.domain.DraftSaveState

/**
 * ChatInputBar
 *
 * Bottom input bar featuring draft text field, inspiration toggle, send button,
 * and persistent draft save indicator.
 */
@Composable
fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    draftState: DraftSaveState,
    onRetryDraftSave: () -> Unit,
    busy: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onToggleInspirations: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onToggleInspirations,
                modifier = Modifier.size(44.dp)
            ) {
                Text("💡", fontSize = 22.sp)
            }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = draftState != DraftSaveState.LOADING,
                placeholder = {
                    Text(
                        if (draftState == DraftSaveState.LOADING) "正在恢复草稿…"
                        else "发送消息（支持 *动作描写*）…"
                    )
                },
                maxLines = 5
            )

            Button(
                onClick = onSend,
                enabled = canSend
            ) {
                Text("发送")
            }
        }

        if (draftState == DraftSaveState.FAILED) {
            TextButton(
                onClick = onRetryDraftSave,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text("草稿未保存，点击重试", color = MaterialTheme.colorScheme.error)
            }
        } else if (draft.isNotEmpty()) {
            Text(
                text = when (draftState) {
                    DraftSaveState.LOADING -> "正在恢复草稿…"
                    DraftSaveState.SAVING -> "正在保存草稿…"
                    else -> "草稿已保存在此设备"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
    }
}
