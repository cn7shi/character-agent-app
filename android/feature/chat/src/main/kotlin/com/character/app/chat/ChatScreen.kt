package com.character.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import com.character.app.designsystem.CharacterAvatar
import com.character.app.domain.*

private fun statusLabel(status: String) = when (status) {
    "pending", "queued" -> "等待回复"
    "running", "streaming" -> "正在回复"
    "cancel_requested" -> "正在停止"
    "cancelled" -> "已停止"
    "failed" -> "未完成"
    else -> "已完成"
}

fun defaultGreeting(name: String, summary: String): String {
    return when {
        name.contains("林舟") -> "*背着登山包站在窗边看地图，转过头对你温和地笑了笑*\n你好呀！我正在规划下一趟旅程呢，今天想和我聊些什么？"
        name.contains("星禾") -> "*合上手中的笔记本，托着下巴好奇地望着你*\n嘿，你来了！今天身边有没有发生什么有趣或特别的事情想跟我分享？"
        summary.isNotBlank() -> "*向你微微颔首致意*\n你好！我是$name。$summary\n今天想聊些什么？"
        name.isNotBlank() -> "*微笑着看向你*\n你好呀，我是$name，很高兴见到你！今天想聊聊什么？"
        else -> "试着说“你好”或“今天有点累”。\n林舟的新会话也可以发送：\n记笔记：周末安排；买咖啡；看电影"
    }
}

/** Parses *action/narration* text into italic styling for immersive roleplay */
fun buildRoleplayAnnotatedString(text: String, isUser: Boolean, italicColor: Color, normalColor: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val regex = Regex("""(\*[^*]+\*|（[^）]+）|\([^\)]+\))""")
    var lastIndex = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > lastIndex) {
            val normal = text.substring(lastIndex, match.range.first)
            builder.pushStyle(SpanStyle(color = normalColor, fontStyle = FontStyle.Normal))
            builder.append(normal)
            builder.pop()
        }
        val actionText = match.value
        builder.pushStyle(SpanStyle(color = italicColor, fontStyle = FontStyle.Italic))
        builder.append(actionText)
        builder.pop()
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val normal = text.substring(lastIndex)
        builder.pushStyle(SpanStyle(color = normalColor, fontStyle = FontStyle.Normal))
        builder.append(normal)
        builder.pop()
    }
    return builder.toAnnotatedString()
}

/** Derives 3 lightweight roleplay inspiration responses from assistant text without hitting external LLM */
internal fun generateInspirations(lastText: String, characterName: String): List<String> {
    val clean = lastText.replace(Regex("""\*.*?\*|（.*?）|\(.*?\)|[\r\n]"""), " ").trim()
    return when {
        clean.contains("？") || clean.contains("?") -> {
            listOf(
                "*认真地想了想* 我觉得挺好的，你觉得呢？",
                "*好奇地看着你* 为什么会这么问呢？我想听听你的看法。",
                "*微笑着摇摇头* 好像确实有点难选，要不你给我点建议？"
            )
        }
        clean.contains("旅行") || clean.contains("旅程") || clean.contains("走") -> {
            listOf(
                "*靠在窗边* 听起来很有趣，你最推荐哪个地方？",
                "*眼睛一亮* 真的很想去看看！要不我们一起做个攻略？",
                "*托着下巴看着你，示意你继续说下去*"
            )
        }
        clean.contains("故事") || clean.contains("日常") || clean.contains("生活") -> {
            listOf(
                "*安静地托着腮* 后来呢？事情是怎么发展的？",
                "*若有所思地点点头* 确实很有感触，换作是我可能也会这样想。",
                "*托着下巴看着你，示意你继续说下去*"
            )
        }
        clean.isNotBlank() -> {
            listOf(
                "*若有所思地点点头* 原来是这样，那接下来呢？",
                "*好奇地凑近了些* 展开讲讲？我很想听更多细节。",
                "*托着下巴倾听着，示意你继续说下去*"
            )
        }
        else -> {
            listOf(
                "*向你微笑着打了个招呼* 今天有什么想聊的吗？",
                "*拉开对面的椅子坐下* 看起来心情不错？",
                "*托着下巴看着你，示意你继续说下去*"
            )
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<Message>,
    pending: List<PendingMessage>,
    runs: List<Run>,
    draft: String,
    busy: Boolean,
    draftState: DraftSaveState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRetryDraftSave: () -> Unit,
    characterName: String = "",
    characterSummary: String = "",
    onRegenerate: (() -> Unit)? = null,
    onSendQuick: ((String) -> Unit)? = null
) {
    val active = runs.firstOrNull { !it.terminal }
    val scroll = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    var showInspirations by remember { mutableStateOf(false) }

    LaunchedEffect(messages.lastOrNull()?.text, pending.size) {
        val count = messages.size + pending.size
        if (count > 0) scroll.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Opening Greeting when conversation is fresh
            if (messages.isEmpty() && pending.isEmpty()) {
                item {
                    val greeting = defaultGreeting(characterName, characterSummary)
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
                            CharacterAvatar(name = characterName.ifBlank { "林舟" }, size = 38.dp, modifier = Modifier.padding(end = 10.dp, top = 2.dp))
                            Surface(shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.widthIn(max = 340.dp).padding(16.dp)) {
                                    Text(
                                        buildRoleplayAnnotatedString(
                                            greeting,
                                            isUser = false,
                                            italicColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                            normalColor = MaterialTheme.colorScheme.onSurface
                                        ),
                                        lineHeight = 22.sp
                                    )
                                    Text("角色开场白", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }

                        // Quick prompt suggestions
                        Text("快捷发送：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp))
                        Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("你好呀！", "今天过得怎么样？", "记笔记：周末安排；看电影").forEach { tip ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.clickable {
                                        if (onSendQuick != null) onSendQuick(tip)
                                        else onDraft(tip)
                                    }
                                ) {
                                    Text(tip, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                val isUser = message.role == "user"
                val isLastAssistant = !isUser && message.id == messages.lastOrNull { it.role == "assistant" }?.id
                val rawText = message.text

                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top
                    ) {
                        if (!isUser) {
                            CharacterAvatar(name = characterName.ifBlank { "林舟" }, size = 36.dp, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                        }

                        Surface(
                            shape = if (isUser) RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                            else RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.widthIn(max = 340.dp).padding(16.dp)) {
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (rawText.isBlank()) Text("…")
                                        else {
                                            Text(
                                                text = buildRoleplayAnnotatedString(
                                                    rawText,
                                                    isUser = isUser,
                                                    italicColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                    normalColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface
                                                ),
                                                lineHeight = 22.sp
                                            )
                                        }
                                    }
                                }

                                if (!isUser) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(statusLabel(message.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(rawText))
                                                    copiedNotice = "已复制"
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("复制", style = MaterialTheme.typography.labelSmall)
                                            }
                                            if (isLastAssistant && active == null && onSendQuick != null) {
                                                TextButton(
                                                    onClick = { onSendQuick("*托着下巴倾听着，示意你继续说下去*") },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("▶ 继续说", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            if (isLastAssistant && active == null && onRegenerate != null) {
                                                TextButton(
                                                    onClick = onRegenerate,
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("🔄 换个回答", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                    runs.find { it.id == message.runId }?.error?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
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
        }

        if (active != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (active.simulated) "模拟回复 · ${statusLabel(active.status)}" else "AI 正在思考 · ${statusLabel(active.status)}")
            TextButton(onClick = { onCancel(active.id) }, enabled = active.status != "cancel_requested") { Text("停止") }
        }

        if (showInspirations) {
            val lastAssistantMsg = messages.lastOrNull { it.role == "assistant" }?.text ?: ""
            val inspirations = generateInspirations(lastAssistantMsg, characterName)
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💡 灵感回复（点击直接填入）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { showInspirations = false }, modifier = Modifier.size(24.dp)) {
                            Text("✕", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        inspirations.forEach { idea ->
                            SuggestionChip(
                                onClick = {
                                    onDraft(idea)
                                    showInspirations = false
                                },
                                label = { Text(idea, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { showInspirations = !showInspirations },
                modifier = Modifier.size(44.dp)
            ) {
                Text("💡", fontSize = 22.sp)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                enabled = draftState != DraftSaveState.LOADING,
                placeholder = { Text(if (draftState == DraftSaveState.LOADING) "正在恢复草稿…" else "发送消息（支持 *动作描写*）…") },
                maxLines = 5
            )
            Button(
                onClick = onSend,
                enabled = !busy && draftState != DraftSaveState.LOADING && draftState != DraftSaveState.FAILED && active == null && pending.isEmpty() && draft.isNotBlank() && draft.codePointCount(0, draft.length) <= 8000
            ) { Text("发送") }
        }

        if (draftState == DraftSaveState.FAILED) TextButton(onClick = onRetryDraftSave, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("草稿未保存，点击重试", color = MaterialTheme.colorScheme.error)
        } else if (draft.isNotEmpty()) Text(
            when (draftState) {
                DraftSaveState.LOADING -> "正在恢复草稿…"
                DraftSaveState.SAVING -> "正在保存草稿…"
                else -> "草稿已保存在此设备"
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
    }
}
