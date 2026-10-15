package com.character.app.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.character.app.chat.components.ChatInspirationsBar
import com.character.app.chat.components.ChatInputBar
import com.character.app.chat.components.ChatMessageItem
import com.character.app.chat.components.PendingMessageCard
import com.character.app.designsystem.CharacterAvatar
import com.character.app.domain.*

/**
 * ChatScreen
 *
 * Full-featured character dialogue screen.
 * Refactored into a lean orchestrator composed of atomic message, input, and inspiration components.
 */
@Composable
fun ChatScreen(
    characterName: String,
    characterSummary: String = "",
    characterEmoji: String? = null,
    messages: List<Message>,
    pending: List<PendingMessage>,
    runs: List<Run>,
    draft: String,
    draftState: DraftSaveState,
    busy: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRetryDraftSave: () -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSendQuick: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val active = runs.firstOrNull { !it.terminal }
    var copiedText by remember { mutableStateOf<String?>(null) }
    var showInspirations by remember { mutableStateOf(false) }

    val lastAssistantMessage = messages.lastOrNull { it.role == "assistant" }
    val lastAssistantId = lastAssistantMessage?.id
    val lastAssistantText = lastAssistantMessage?.text ?: ""

    // Auto-scroll to the newest message
    LaunchedEffect(messages.size, pending.size, active?.output?.blocks?.size) {
        val total = messages.size + pending.size
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("chat-screen")
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Empty state greeting
            if (messages.isEmpty() && pending.isEmpty() && active == null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CharacterAvatar(name = characterName, avatarId = characterEmoji, modifier = Modifier.size(56.dp))
                            Text(
                                text = ChatFormatters.defaultGreeting(characterName, characterSummary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Messages list
            items(messages, key = { it.id }) { message ->
                ChatMessageItem(
                    message = message,
                    characterName = characterName,
                    characterEmoji = characterEmoji,
                    isLastAssistant = message.id == lastAssistantId,
                    activeRun = active,
                    runError = runs.find { it.id == message.runId }?.error,
                    onCopy = { copiedText = it },
                    onSendQuick = onSendQuick,
                    onRegenerate = onRegenerate
                )
            }

            // Pending Outbox messages
            items(pending, key = { "pending:${it.id}" }) { item ->
                PendingMessageCard(
                    pendingItem = item,
                    busy = busy,
                    onRetry = onRetry,
                    onDismiss = onDismiss
                )
            }
        }

        // Active run status bar
        if (active != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (active.simulated) "模拟回复 · ${ChatFormatters.statusLabel(active.status)}"
                    else "AI 正在思考 · ${ChatFormatters.statusLabel(active.status)}"
                )
                TextButton(
                    onClick = { onCancel(active.id) },
                    enabled = active.status != "cancel_requested"
                ) {
                    Text("停止")
                }
            }
        }

        // Inspirations Tray
        if (showInspirations) {
            val inspirations = remember(lastAssistantText, characterName) {
                ChatFormatters.generateInspirations(lastAssistantText, characterName)
            }
            ChatInspirationsBar(
                inspirations = inspirations,
                onSelectInspiration = { idea ->
                    onDraft(idea)
                    showInspirations = false
                },
                onDismiss = { showInspirations = false }
            )
        }

        // Bottom Input Bar
        val canSend = !busy &&
                draftState != DraftSaveState.LOADING &&
                draftState != DraftSaveState.FAILED &&
                active == null &&
                pending.isEmpty() &&
                draft.isNotBlank() &&
                draft.codePointCount(0, draft.length) <= 8000

        ChatInputBar(
            draft = draft,
            onDraftChange = onDraft,
            draftState = draftState,
            onRetryDraftSave = onRetryDraftSave,
            busy = busy,
            canSend = canSend,
            onSend = onSend,
            onToggleInspirations = { showInspirations = !showInspirations }
        )
    }
}
