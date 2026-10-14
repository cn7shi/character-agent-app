package com.character.app.characters

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.character.app.characters.components.HomeBottomBar
import com.character.app.characters.components.HomeStoryCards
import com.character.app.characters.components.HomeTopBar
import com.character.app.characters.components.InspirationDialog
import com.character.app.characters.components.PersonaSettingsDialog
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
    onOpenConversation: (Conversation) -> Unit,
    onStartWithCharacter: (PlazaCharacter) -> Unit,
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

    val currentChar = uiState.currentChar

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
                        } else if (totalDragY > 60f) {
                            VoicePlayer.stop()
                            uiState = uiState.previousCharacter()
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
            HomeTopBar(
                currentChar = currentChar,
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
                onOpenPersona = { uiState = uiState.setPersonaSheet(true) }
            )

            Spacer(modifier = Modifier.weight(1f))

            HomeStoryCards(
                synopsis = currentChar.synopsis,
                actionText = uiState.displayActionText,
                dialogueText = uiState.displayDialogueText,
                characterName = currentChar.name,
                voiceDuration = uiState.currentPage.voiceDuration,
                onAdvanceScript = {
                    VoicePlayer.stop()
                    uiState = uiState.advanceScript()
                }
            )

            HomeBottomBar(
                inputDraft = uiState.inputDraft,
                onInputDraftChange = { uiState = uiState.updateDraft(it) },
                isAiGenerating = uiState.isAiGenerating,
                characterName = currentChar.name,
                onOpenInspiration = { uiState = uiState.setInspirationSheet(true) },
                onSendMessage = { text ->
                    val userText = text.trim()
                    uiState = uiState.startUserSending()
                    Toast.makeText(context, "已发送：“$userText”", Toast.LENGTH_SHORT).show()

                    onSendMessage(currentChar.id, userText) { act, dia ->
                        uiState = uiState.finishAiGenerating(act, dia)
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

        // AI Roleplay Inspiration Dialog
        if (uiState.showInspirationSheet) {
            InspirationDialog(
                characterName = currentChar.name,
                onDismiss = { uiState = uiState.setInspirationSheet(false) },
                onSelect = { inspiration ->
                    uiState = uiState.updateDraft(inspiration).setInspirationSheet(false)
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
