package com.character.app.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.character.app.domain.CharacterDraft

/**
 * Clean character editing screen.
 * Business values and callbacks decoupled: another layout/theme reuses the same editing flow.
 */
@Composable
fun CharacterEditorScreen(
    draft: CharacterDraft,
    busy: Boolean,
    pending: Boolean,
    onChange: (CharacterDraft) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    fun String.count() = codePointCount(0, length)
    val valid = draft.name.trim().count() in 1..100 &&
        draft.summary.trim().count() <= 500 &&
        draft.systemPrompt.trim().count() in 1..8000
    val editable = !busy && !pending

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = if (draft.version == 0) "创建角色" else "编辑角色",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("仅自己可见。设定好的人设将实时指导 Groq 大模型，已有对话保留创建时的设定。")
        if (pending) {
            Text("上次保存结果待确认。可以重试原内容，或重新读取已保存版本。", color = MaterialTheme.colorScheme.primary)
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text("角色名称") },
            supportingText = { Text("必填，最多 100 字") },
            singleLine = true,
        )

        OutlinedTextField(
            value = draft.summary,
            onValueChange = { onChange(draft.copy(summary = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text("角色简介") },
            supportingText = { Text("可选，最多 500 字；简述身份与性格") },
            minLines = 2,
            maxLines = 4,
        )

        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { onChange(draft.copy(systemPrompt = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text("角色详细人设") },
            supportingText = { Text("必填，最多 8000 字；描述性格、语气习惯、与你的关系，支持设定【开场白】或 *动作描写* 习惯") },
            minLines = 5,
            maxLines = 10,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("允许保存笔记")
                Text("角色可通过笔记工具帮你记录日常安排。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = draft.notesEnabled,
                onCheckedChange = { onChange(draft.copy(notesEnabled = it)) },
                enabled = editable,
            )
        }

        Text(
            text = "✨ 已接入 Groq (Qwen 27B) 大模型驱动，支持流式对话与动作描写渲染。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Button(
            onClick = onSave,
            enabled = !busy && valid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (pending) "重试原内容" else "保存角色")
        }

        if (draft.version > 0 || pending) {
            TextButton(onClick = onReload, enabled = !busy) {
                Text("放弃修改，读取已保存版本")
            }
        }

        TextButton(onClick = onClose, enabled = !busy) {
            Text(if (pending) "稍后继续" else "取消编辑")
        }
    }
}
