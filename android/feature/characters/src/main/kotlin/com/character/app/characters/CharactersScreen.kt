package com.character.app.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.character.app.domain.Character
import com.character.app.domain.Conversation

@Composable
fun CharactersScreen(characters: List<Character>, conversations: List<Conversation>, busy: Boolean,
                     onNew: (Character) -> Unit, onOpen: (Conversation) -> Unit, onRefresh: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("遇见角色", style = MaterialTheme.typography.headlineMedium); Text("从一次对话开始", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            }
        }
        if (characters.isEmpty()) item { Text("还没有角色资料。连接开发服务后刷新；已缓存的资料可离线查看。") }
        items(characters, key = { "character:${it.id}" }) { character ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(character.name, style = MaterialTheme.typography.titleLarge)
                    Text(character.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onNew(character) }, enabled = !busy) { Text("开启新对话") }
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
