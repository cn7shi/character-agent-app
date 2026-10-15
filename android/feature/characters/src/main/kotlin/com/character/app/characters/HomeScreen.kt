package com.character.app.characters

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import com.character.app.characters.components.HomeAssistantBubble
import com.character.app.characters.components.HomeAiTypingBubble
import com.character.app.characters.components.HomeBottomBar
import com.character.app.characters.components.HomeStoryCards
import com.character.app.characters.components.HomeSynopsisCard
import com.character.app.characters.components.HomeTopBar
import com.character.app.characters.components.HomeUserBubble
import com.character.app.characters.components.PersonaSettingsDialog
import com.character.app.characters.components.RenameConversationDialog
import com.character.app.domain.Character
import com.character.app.domain.Conversation

/**
 * HomeScreen
 *
 * High-fidelity full-bleed immersion character dialogue screen.
 * Refactored into a clean, reactive orchestrator driven by [HomeUiState] with zero inline business logic.
 */
@Composable
fun HomeScreen(
    conversations: List<Conversation>,
    characters: List<Character>,
    selectedConversation: Conversation? = null,
    onOpenConversation: (Conversation) -> Unit,
    onStartWithCharacter: (PlazaCharacter) -> Unit,
    onRenameConversation: ((conversationId: String, newTitle: String) -> Unit)? = null,
    onOpenPlaza: () -> Unit = {},
    onOpenExplore: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    activeCharacterId: String? = null,
    latestAssistantMessage: String? = null,
    onSendMessage: (characterId: String, text: String, onResult: (action: String, dialogue: String) -> Unit) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var uiState by remember {
        mutableStateOf(HomeUiState().selectCharacterById(activeCharacterId))
    }
    var showRenameDialog by remember { mutableStateOf(false) }
    val currentChar = uiState.currentChar

    // Synchronize character if activeCharacterId changes from external tabs
    LaunchedEffect(activeCharacterId) {
        uiState = uiState.selectCharacterById(activeCharacterId)
    }

    // Synchronize incoming assistant streaming/replies
    LaunchedEffect(latestAssistantMessage) {
        if (!latestAssistantMessage.isNullOrBlank()) {
            uiState = uiState.applyAssistantReply(latestAssistantMessage)
        }
    }

    // Safety timeout: If backend is offline or slow on real device, fall back to in-character dialogue
    LaunchedEffect(uiState.isAiGenerating) {
        if (uiState.isAiGenerating) {
            delay(3500)
            if (uiState.isAiGenerating) {
                val page = uiState.pages.getOrNull(uiState.scriptPageIndex)
                val fallbackReply = page?.dialogueText ?: currentChar.quote
                val fallbackAction = page?.actionText ?: "（眸底掠过一丝深邃的审视，语气微沉）"
                uiState = uiState.addAssistantReply(fallbackAction, fallbackReply)
                    .advanceScript()
                    .setInspirationSheet(true)
            }
        }
    }

    // Audio lifecycle management
    DisposableEffect(Unit) {
        onDispose { VoicePlayer.stop() }
    }
    DisposableEffect(currentChar.id, uiState.scriptPageIndex, uiState.liveDialogueText) {
        onDispose { VoicePlayer.stop() }
    }

    // Vertical drag gesture detection for character switching
    var totalDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D11))
            .testTag("home-screen")
            .pointerInput(currentChar.id) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount
                    },
                    onDragEnd = {
                        if (totalDragY < -60f) {
                            VoicePlayer.stop()
                            uiState = uiState.nextCharacter()
                            onStartWithCharacter(uiState.currentChar)
                        } else if (totalDragY > 60f) {
                            VoicePlayer.stop()
                            uiState = uiState.previousCharacter()
                            onStartWithCharacter(uiState.currentChar)
                        }
                        totalDragY = 0f
                    },
                    onDragCancel = { totalDragY = 0f }
                )
            }
    ) {
        // 1. Full-bleed Artwork Background
        Image(
            painter = painterResource(currentChar.bgResId),
            contentDescription = currentChar.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 2. Atmospheric Top Gradient
        AtmosphericTopGradient(Modifier.align(Alignment.TopCenter))

        // 3. Atmospheric Bottom Gradient
        AtmosphericBottomGradient(Modifier.align(Alignment.BottomCenter))

        // 4. Foreground Overlay Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            val currentConv = selectedConversation ?: run {
                val charEntity = characters.find { it.name == currentChar.name || it.id == currentChar.id }
                conversations.find { (charEntity != null && it.characterId == charEntity.id) || it.characterId == currentChar.id || it.title == currentChar.name }
            }

            HomeTopBar(
                currentChar = currentChar,
                conversationTitle = currentConv?.title,
                isFollowed = uiState.isFollowed,
                onToggleFollow = {
                    uiState = uiState.toggleFollow()
                    val msg = if (uiState.isFollowed) "已关注 ${currentChar.name}" else "已取消关注"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                onCycleCharacter = {
                    VoicePlayer.stop()
                    uiState = uiState.nextCharacter()
                    Toast.makeText(context, "切换角色：${uiState.currentChar.name}", Toast.LENGTH_SHORT).show()
                },
                onOpenPlaza = onOpenPlaza,
                onOpenPersona = { uiState = uiState.setPersonaSheet(true) },
                onRenameConversation = if (currentConv != null && onRenameConversation != null) {
                    { showRenameDialog = true }
                } else null
            )

            val listState = rememberLazyListState()

            // Whenever message count changes or AI begins/finishes generating, smoothly scroll to newest message
            LaunchedEffect(uiState.currentMessages.size, uiState.isAiGenerating) {
                val target = uiState.currentMessages.size + (if (uiState.isAiGenerating) 1 else 0)
                if (target > 0) {
                    listState.animateScrollToItem(target)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
            ) {
                // 1. Character Background Synopsis Card
                item(key = "synopsis-${currentChar.id}") {
                    HomeSynopsisCard(synopsis = currentChar.synopsis)
                }

                // 2. WeChat-style Messages Flow (User on right, Assistant on left)
                items(uiState.currentMessages, key = { it.id }) { msg ->
                    if (msg.role == "user") {
                        HomeUserBubble(text = msg.dialogueText)
                    } else {
                        HomeAssistantBubble(
                            actionText = msg.actionText ?: "",
                            dialogueText = msg.dialogueText,
                            characterName = currentChar.name,
                            voiceDuration = msg.voiceDuration,
                            onAdvance = {
                                VoicePlayer.stop()
                                uiState = uiState.advanceScript()
                            }
                        )
                    }
                }

                // 3. AI Thinking/Generating Indicator
                if (uiState.isAiGenerating) {
                    item(key = "generating") {
                        HomeAiTypingBubble(characterName = currentChar.name)
                    }
                }
            }

            HomeBottomBar(
                inputDraft = uiState.inputDraft,
                onInputDraftChange = { uiState = uiState.updateDraft(it) },
                isAiGenerating = uiState.isAiGenerating,
                characterName = currentChar.name,
                isInspirationVisible = uiState.showInspirationSheet,
                onToggleInspiration = {
                    uiState = uiState.setInspirationSheet(!uiState.showInspirationSheet)
                },
                onSelectInspiration = { inspiration ->
                    uiState = uiState.updateDraft(inspiration)
                },
                onSendMessage = { text ->
                    val userText = text.trim()
                    uiState = uiState.addUserMessage(userText).setInspirationSheet(false)
                    Toast.makeText(context, "已发送：“$userText”", Toast.LENGTH_SHORT).show()

                    onSendMessage(currentChar.id, userText) { act, dia ->
                        uiState = uiState.addAssistantReply(act, dia).setInspirationSheet(true)
                    }
                }
            )
        }

        // Global Persona Settings Dialog
        if (uiState.showPersonaSheet) {
            PersonaSettingsDialog(
                onDismiss = { uiState = uiState.setPersonaSheet(false) },
                onSave = { nickname, gender, _ ->
                    uiState = uiState.setPersonaSheet(false)
                    Toast.makeText(context, "全局设定已保存：$nickname ($gender)", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Rename Conversation Dialog
        val renameTarget = selectedConversation ?: run {
            val charEntity = characters.find { it.name == currentChar.name || it.id == currentChar.id }
            conversations.find { (charEntity != null && it.characterId == charEntity.id) || it.characterId == currentChar.id || it.title == currentChar.name }
        }
        if (showRenameDialog && renameTarget != null) {
            RenameConversationDialog(
                initialTitle = renameTarget.title,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newTitle ->
                    onRenameConversation?.invoke(renameTarget.id, newTitle)
                    showRenameDialog = false
                    Toast.makeText(context, "会话已重命名为：$newTitle", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun AtmosphericTopGradient(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.38f)
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0xD9040406),
                    0.45f to Color(0x73040406),
                    1.0f to Color.Transparent
                )
            )
    )
}

@Composable
private fun AtmosphericBottomGradient(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.40f to Color(0x66060608),
                    0.75f to Color(0xD1060608),
                    1.0f to Color(0xF5060608)
                )
            )
    )
}
