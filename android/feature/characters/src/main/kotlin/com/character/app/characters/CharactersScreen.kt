package com.character.app.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.character.app.domain.Character
import com.character.app.domain.Conversation

@Composable
fun CharactersScreen(characters: List<Character>, conversations: List<Conversation>, busy: Boolean,
                     onNew: (Character) -> Unit, onOpen: (Conversation) -> Unit, onRefresh: () -> Unit,
                     canManage: Boolean, hasPendingEdit: Boolean, onAdd: () -> Unit,
                     onEdit: (Character) -> Unit, onDelete: (Character) -> Unit) {
    var deleting by remember { mutableStateOf<Character?>(null) }
    deleting?.let { character ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除角色？") },
            text = { Text("删除“${character.name}”后将无法开启新对话。已有聊天、草稿和笔记会保留，旧对话仍可继续。") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(character) }, enabled = !busy) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("遇见角色", style = MaterialTheme.typography.headlineMedium); Text("从一次对话开始", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            }
        }
        if (canManage) item {
            Button(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasPendingEdit) "继续确认角色保存" else "创建角色")
            }
            if (hasPendingEdit) Text("上次保存结果待确认，继续处理会沿用原角色，不会重新创建。", style = MaterialTheme.typography.bodySmall)
        }
        if (characters.isEmpty()) item { Text("还没有角色资料。连接开发服务后刷新；已缓存的资料可离线查看。") }
        items(characters, key = { "character:${it.id}" }) { character ->
            Card(Modifier.fillMaxWidth().testTag("character-card")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(character.name, style = MaterialTheme.typography.titleLarge)
                    Text(character.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (character.editable) "我的角色 · 仅自己可见" else "内置角色", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onNew(character) }, enabled = !busy) { Text("开启新对话") }
                        if (character.editable && canManage) {
                            TextButton(onClick = { onEdit(character) }, enabled = !busy && !hasPendingEdit) { Text("编辑") }
                            TextButton(onClick = { deleting = character }, enabled = !busy && !hasPendingEdit) { Text("删除") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)); Text("最近对话", style = MaterialTheme.typography.titleLarge) }
        if (conversations.isEmpty()) item { Text("你的对话会保留在这里。") }
        items(conversations, key = { "conversation:${it.id}" }) { conversation ->
            OutlinedCard(onClick = { onOpen(conversation) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) { Text(conversation.title); Text("继续聊天  ›", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
