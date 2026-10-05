package com.character.app.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.character.app.domain.Note

@Composable
fun NotesScreen(notes: List<Note>, busy: Boolean, onRefresh: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("笔记", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            }
        }
        if (notes.isEmpty()) item { Text("暂时没有笔记。\n在林舟的新会话中发送“记笔记：……”即可保存。") }
        items(notes, key = { it.id }) { note ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(note.title, style = MaterialTheme.typography.titleLarge)
                    SelectionContainer { Text(note.content) }
                    Text("已保存 · 可离线查看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
