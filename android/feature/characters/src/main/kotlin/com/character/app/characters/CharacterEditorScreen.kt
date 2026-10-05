package com.character.app.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.character.app.domain.CharacterDraft

/** Only business values and callbacks: another theme/layout reuses the same editing flow. */
@Composable
fun CharacterEditorScreen(draft: CharacterDraft, busy: Boolean, pending: Boolean,
                          onChange: (CharacterDraft) -> Unit, onSave: () -> Unit,
                          onReload: () -> Unit, onClose: () -> Unit) {
    fun String.count() = codePointCount(0, length)
    val valid = draft.name.trim().count() in 1..100 && draft.summary.trim().count() <= 500 && draft.systemPrompt.trim().count() in 1..8000
    val editable = !busy && !pending
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (draft.version == 0) "创建角色" else "编辑角色", style = MaterialTheme.typography.headlineMedium)
        Text("仅自己可见。修改人设和能力会用于新对话，已有对话保留创建时的设定。")
        if (pending) Text("上次保存结果待确认。可以重试原内容，或重新读取已保存版本。", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) }, Modifier.fillMaxWidth(), enabled = editable,
            label = { Text("角色名称") }, supportingText = { Text("必填，最多 100 字") }, singleLine = true)
        OutlinedTextField(draft.summary, { onChange(draft.copy(summary = it)) }, Modifier.fillMaxWidth(), enabled = editable,
            label = { Text("角色简介") }, supportingText = { Text("可选，最多 500 字") }, minLines = 2, maxLines = 4)
        OutlinedTextField(draft.systemPrompt, { onChange(draft.copy(systemPrompt = it)) }, Modifier.fillMaxWidth(), enabled = editable,
            label = { Text("角色人设") }, supportingText = { Text("必填，最多 8000 字；描述性格、说话方式和相处设定") }, minLines = 4, maxLines = 8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("允许保存笔记"); Text("角色可通过笔记工具帮你记录内容。", style = MaterialTheme.typography.bodySmall) }
            Switch(draft.notesEnabled, { onChange(draft.copy(notesEnabled = it)) }, enabled = editable)
        }
        Text("当前演示仍使用固定回复。真实模型配置后才会按人设生成不同回复。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onSave, enabled = !busy && valid, modifier = Modifier.fillMaxWidth()) { Text(if (pending) "重试原内容" else "保存角色") }
        if (draft.version > 0 || pending) TextButton(onClick = onReload, enabled = !busy) { Text("放弃修改，读取已保存版本") }
        TextButton(onClick = onClose, enabled = !busy) { Text(if (pending) "稍后继续" else "取消编辑") }
    }
}
