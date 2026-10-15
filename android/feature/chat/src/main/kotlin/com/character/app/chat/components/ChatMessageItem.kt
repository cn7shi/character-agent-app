package com.character.app.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.character.app.chat.ChatFormatters
import com.character.app.designsystem.CharacterAvatar
import com.character.app.domain.Message
import com.character.app.domain.Run

/**
 * ChatMessageItem
 *
 * Message bubble component rendering either user or assistant message with avatars,
 * copy actions, roleplay formatting, and quick continuation buttons.
 */
@Composable
fun ChatMessageItem(
    message: Message,
    characterName: String,
    characterEmoji: String?,
    isLastAssistant: Boolean,
    activeRun: Run?,
    runError: String?,
    onCopy: (String) -> Unit,
    onSendQuick: ((String) -> Unit)?,
    onRegenerate: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            CharacterAvatar(
                name = characterName,
                avatarId = characterEmoji,
                modifier = Modifier.padding(end = 8.dp).size(36.dp)
            )
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (isUser) 0.dp else 1.dp
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SelectionContainer {
                        if (isUser) {
                            Text(
                                text = ChatFormatters.buildRoleplayAnnotatedString(
                                    text = message.text,
                                    isUser = true,
                                    italicColor = Color(0xFFD0BCFF),
                                    normalColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            Text(
                                text = ChatFormatters.buildRoleplayAnnotatedString(
                                    text = message.text,
                                    isUser = false,
                                    italicColor = MaterialTheme.colorScheme.primary,
                                    normalColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    // Bottom bar of message card: Copy button + Quick continue / Regenerate
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(message.text))
                                onCopy(message.text)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("复制", style = MaterialTheme.typography.labelSmall)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isLastAssistant && activeRun == null && onSendQuick != null) {
                                TextButton(
                                    onClick = { onSendQuick("*托着下巴倾听着，示意你继续说下去*") },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("▶ 继续说", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (isLastAssistant && activeRun == null && onRegenerate != null) {
                                TextButton(
                                    onClick = onRegenerate,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        "🔄 换个回答",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (runError != null) {
                Text(
                    text = runError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
