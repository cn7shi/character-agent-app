package com.character.app.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.character.app.domain.*

private fun statusLabel(status: String) = when (status) {
    "pending", "queued" -> "等待回复"
    "running", "streaming" -> "正在回复"
    "cancel_requested" -> "正在停止"
    "cancelled" -> "已停止"
    "failed" -> "未完成"
    else -> "已完成"
}

@Composable
fun ChatScreen(messages: List<Message>, pending: List<PendingMessage>, runs: List<Run>, draft: String, busy: Boolean, draftState: DraftSaveState,
               onDraft: (String) -> Unit, onSend: () -> Unit, onCancel: (String) -> Unit,
               onRetry: (String) -> Unit, onDismiss: (String) -> Unit, onRetryDraftSave: () -> Unit) {
    val active = runs.firstOrNull { !it.terminal }
    val scroll = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.text, pending.size) {
        val count = messages.size + pending.size
        if (count > 0) scroll.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start) {
                    Surface(shape = MaterialTheme.shapes.large, color = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.widthIn(max = 360.dp).padding(16.dp)) {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (message.text.isBlank()) Text("…")
                                    else message.blocks.forEach { block ->
                                        Text(block.text, fontStyle = if (block.kind == "narration") FontStyle.Italic else FontStyle.Normal)
                                    }
                                }
                            }
                            if (message.role == "assistant") {
                                Text(statusLabel(message.status), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                                runs.find { it.id == message.runId }?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
            items(pending, key = { "pending:${it.id}" }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.text)
                        Text(item.error ?: "正在提交…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.blocked) TextButton(onClick = { onDismiss(item.id) }, enabled = !busy) { Text("移除未受理消息") }
                        else TextButton(onClick = { onRetry(item.id) }, enabled = !busy) { Text("用原请求重试") }
                    }
                }
            }
            if (messages.isEmpty() && pending.isEmpty()) item {
                Text("试着说“你好”或“今天有点累”。\n林舟的新会话也可以发送：\n记笔记：周末安排；买咖啡；看电影", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (active != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (active.simulated) "模拟回复 · ${statusLabel(active.status)}" else statusLabel(active.status))
            TextButton(onClick = { onCancel(active.id) }, enabled = active.status != "cancel_requested") { Text("停止") }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f),
                enabled = draftState != DraftSaveState.LOADING, placeholder = { Text(if (draftState == DraftSaveState.LOADING) "正在恢复草稿…" else "发送消息…") }, maxLines = 5)
            Button(onClick = onSend, enabled = !busy && draftState != DraftSaveState.LOADING && draftState != DraftSaveState.FAILED && active == null && pending.isEmpty() && draft.isNotBlank() && draft.codePointCount(0, draft.length) <= 8000) { Text("发送") }
        }
        if (draftState == DraftSaveState.FAILED) TextButton(onClick = onRetryDraftSave, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("草稿未保存，点击重试", color = MaterialTheme.colorScheme.error)
        } else if (draft.isNotEmpty()) Text(when (draftState) {
            DraftSaveState.LOADING -> "正在恢复草稿…"
            DraftSaveState.SAVING -> "正在保存草稿…"
            else -> "草稿已保存在此设备"
        },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
    }
}
