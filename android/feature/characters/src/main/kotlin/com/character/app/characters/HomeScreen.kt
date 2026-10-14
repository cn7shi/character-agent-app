package com.character.app.characters

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.character.app.domain.Character
import com.character.app.domain.Conversation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()

    // Active character index
    var charIndex by remember {
        mutableIntStateOf(
            if (activeCharacterId != null) {
                val found = PLAZA_CHARACTERS.indexOfFirst { it.id == activeCharacterId }
                if (found >= 0) found else 0
            } else 0
        )
    }

    // Keep in sync if activeCharacterId changes from external tabs
    LaunchedEffect(activeCharacterId) {
        if (activeCharacterId != null) {
            val found = PLAZA_CHARACTERS.indexOfFirst { it.id == activeCharacterId }
            if (found >= 0) charIndex = found
        }
    }

    val currentChar = PLAZA_CHARACTERS[charIndex.coerceIn(0, PLAZA_CHARACTERS.lastIndex)]


    // Multi-turn dialogue script progression
    var scriptPageIndex by remember(currentChar.id) { mutableIntStateOf(0) }
    val pages = currentChar.pages.ifEmpty {
        listOf(ScriptPage(1, 5, "(${currentChar.quote})", currentChar.quote))
    }
    val currentPage = pages[scriptPageIndex.coerceIn(0, pages.lastIndex)]

    // Live AI override states for live chat
    var liveActionText by remember(currentChar.id) { mutableStateOf<String?>(null) }
    var liveDialogueText by remember(currentChar.id) { mutableStateOf<String?>(null) }
    var isAiGenerating by remember { mutableStateOf(false) }

    LaunchedEffect(latestAssistantMessage) {
        if (!latestAssistantMessage.isNullOrBlank()) {
            val parenRegex = Regex("""[\(（\*](.*?)[\)）\*]""")
            val match = parenRegex.find(latestAssistantMessage)
            val act = match?.groupValues?.get(1)?.let { "（$it）" } ?: "（若有所思地看着你）"
            val dia = parenRegex.replace(latestAssistantMessage, "").trim().ifEmpty { latestAssistantMessage }
            liveActionText = act
            liveDialogueText = dia
            isAiGenerating = false
        }
    }


    // Follow status
    var isFollowed by remember(currentChar.id) { mutableStateOf(false) }

    // Voice simulation playback
    var isVoicePlaying by remember(currentChar.id) { mutableStateOf(false) }
    var voiceCountdown by remember(currentChar.id) { mutableIntStateOf(currentPage.voiceDuration) }

    // Input draft
    var inputDraft by remember { mutableStateOf("") }

    // Dialog sheets
    var showPersonaSheet by remember { mutableStateOf(false) }
    var showInspirationSheet by remember { mutableStateOf(false) }

    // Countdown timer for voice simulation
    LaunchedEffect(isVoicePlaying) {
        if (isVoicePlaying) {
            voiceCountdown = currentPage.voiceDuration
            while (voiceCountdown > 0) {
                delay(1000)
                voiceCountdown--
            }
            isVoicePlaying = false
            voiceCountdown = currentPage.voiceDuration
        }
    }

    // Swipe gesture detection
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
                            // Swiped UP -> Next character
                            charIndex = (charIndex + 1) % PLAZA_CHARACTERS.size
                            scriptPageIndex = 0
                            liveActionText = null
                            liveDialogueText = null
                            isVoicePlaying = false
                        } else if (totalDragY > 60f) {
                            // Swiped DOWN -> Previous character
                            charIndex = (charIndex - 1 + PLAZA_CHARACTERS.size) % PLAZA_CHARACTERS.size
                            scriptPageIndex = 0
                            liveActionText = null
                            liveDialogueText = null
                            isVoicePlaying = false
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

        // 2. Atmospheric Top Gradient (linear-gradient(180deg, rgba(4,4,6,0.85)...))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xD9040406),
                        0.45f to Color(0x73040406),
                        1.0f to Color.Transparent
                    )
                )
        )

        // 3. Atmospheric Bottom Gradient (linear-gradient(0deg, rgba(6,6,8,0.96)...))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.40f to Color(0x66060608),
                        0.75f to Color(0xD1060608),
                        1.0f to Color(0xF5060608)
                    )
                )
        )

        // 4. Foreground Overlay Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // --- TOP HEADER ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Profile Pill & Comment Pill
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Profile Pill
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xB816171C))
                            .border(1.dp, Color(0x1FFFFFFF), CircleShape)
                            .clickable {
                                // Cycle to next character on avatar pill tap
                                charIndex = (charIndex + 1) % PLAZA_CHARACTERS.size
                                scriptPageIndex = 0
                                liveActionText = null
                                liveDialogueText = null
                                Toast.makeText(context, "切换角色：${PLAZA_CHARACTERS[charIndex].name}", Toast.LENGTH_SHORT).show()
                            }
                            .padding(start = 3.dp, top = 3.dp, bottom = 3.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(currentChar.avatarResId),
                            contentDescription = currentChar.name,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color(0x33FFFFFF), CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = currentChar.name,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text("❤️", fontSize = 9.sp)
                                Text(
                                    text = currentChar.likes,
                                    fontSize = 10.5.sp,
                                    color = Color(0x99FFFFFF)
                                )
                            }
                        }
                        // Follow button
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (isFollowed) Color(0xFFF5B041) else Color(0x2EFFFFFF))
                                .clickable {
                                    isFollowed = !isFollowed
                                    val msg = if (isFollowed) "已关注 ${currentChar.name}" else "已取消关注"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isFollowed) "✓" else "+",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFollowed) Color(0xFF111111) else Color.White
                            )
                        }
                    }

                    // Comment Pill
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xB816171C))
                            .border(1.dp, Color(0x1FFFFFFF), CircleShape)
                            .clickable {
                                Toast.makeText(context, "${currentChar.comments} 条精彩互动讨论", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("💬", fontSize = 12.sp)
                        Text(
                            text = currentChar.comments,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }

                // Right: Search & Profile Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Search Button (Navigates to Plaza)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                            .clickable { onOpenPlaza() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔍", fontSize = 16.sp)
                    }

                    // Profile Button (Navigates to Persona)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                            .clickable { showPersonaSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👤", fontSize = 16.sp)
                    }
                }
            }

            // --- MUSIC BAR & PERSONA ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Music Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xA614141A))
                        .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(20.dp))
                        .clickable {
                            Toast.makeText(context, "正在播放背景原声: ${currentChar.bgm}", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("🎵", fontSize = 11.sp)
                    Text(
                        text = currentChar.bgm,
                        fontSize = 11.5.sp,
                        color = Color(0xE6FFFFFF)
                    )
                }

                // Persona Trigger Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xA614141A))
                        .border(1.dp, Color(0x1FFFFFFF), CircleShape)
                        .clickable { showPersonaSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚙️", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // --- INTERACTIVE STORY CARDS AREA ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Synopsis Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB816171C))
                        .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x33FFFFFF))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "简介",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentChar.synopsis,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = Color(0xCCFFFFFF),
                            maxLines = 3
                        )
                    }
                }

                // 2. Dialogue Card with Voice Pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    // The Card Body
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xCC14151A))
                            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(18.dp))
                            .clickable {
                                // Tapping dialogue card advances multi-turn script!
                                if (liveDialogueText == null) {
                                    scriptPageIndex = (scriptPageIndex + 1) % pages.size
                                    isVoicePlaying = false
                                } else {
                                    // Reset back to pre-scripted pages
                                    liveActionText = null
                                    liveDialogueText = null
                                }
                            }
                            .padding(top = 22.dp, start = 14.dp, end = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val displayAction = liveActionText ?: currentPage.actionText
                        val displayDialogue = liveDialogueText ?: currentPage.dialogueText

                        // Action / Narrative text in italics
                        Text(
                            text = displayAction,
                            fontSize = 13.sp,
                            lineHeight = 19.5.sp,
                            color = Color(0xB3FFFFFF)
                        )

                        // Spoken Dialogue in bold white text
                        Text(
                            text = displayDialogue,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 21.sp,
                            color = Color.White
                        )
                    }

                    // Floating Voice Pill (Top-Left of Dialogue Card)
                    val infiniteTransition = rememberInfiniteTransition(label = "wave")
                    val waveHeight1 by infiniteTransition.animateFloat(
                        initialValue = 4f, targetValue = 12f,
                        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "h1"
                    )
                    val waveHeight2 by infiniteTransition.animateFloat(
                        initialValue = 10f, targetValue = 4f,
                        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse), label = "h2"
                    )
                    val waveHeight3 by infiniteTransition.animateFloat(
                        initialValue = 5f, targetValue = 13f,
                        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "h3"
                    )
                    val waveHeight4 by infiniteTransition.animateFloat(
                        initialValue = 12f, targetValue = 5f,
                        animationSpec = infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse), label = "h4"
                    )

                    Row(
                        modifier = Modifier
                            .offset(x = 14.dp, y = (-10).dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xF0202128))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                            .clickable {
                                isVoicePlaying = !isVoicePlaying
                                if (isVoicePlaying) {
                                    Toast.makeText(context, "正在播放专属角色语音原声...", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(if (isVoicePlaying) "⏸" else "▶", fontSize = 10.sp, color = Color.White)
                        // 4 Wave bars
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(14.dp)
                        ) {
                            Box(Modifier.width(2.dp).height(if (isVoicePlaying) waveHeight1.dp else 4.dp).background(Color.White))
                            Box(Modifier.width(2.dp).height(if (isVoicePlaying) waveHeight2.dp else 8.dp).background(Color.White))
                            Box(Modifier.width(2.dp).height(if (isVoicePlaying) waveHeight3.dp else 12.dp).background(Color.White))
                            Box(Modifier.width(2.dp).height(if (isVoicePlaying) waveHeight4.dp else 6.dp).background(Color.White))
                        }
                        Text(
                            text = "${if (isVoicePlaying) voiceCountdown else currentPage.voiceDuration}\"",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // --- MEDIA QUICK ACTIONS ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Photo Button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xA61C1D24))
                        .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                        .clickable { Toast.makeText(context, "打开角色海报画廊", Toast.LENGTH_SHORT).show() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🖼️", fontSize = 16.sp)
                }

                // Video Button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xA61C1D24))
                        .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                        .clickable { Toast.makeText(context, "切换角色动态交互视频", Toast.LENGTH_SHORT).show() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎬", fontSize = 16.sp)
                }
            }

            // --- CHAT INPUT BAR CAPSULE ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mic Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x261A1B22))
                        .clickable { Toast.makeText(context, "按住开始语音交流...", Toast.LENGTH_SHORT).show() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎙️", fontSize = 20.sp)
                }

                // Input Capsule
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xC01A1B22))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (inputDraft.isEmpty()) {
                            Text(
                                text = "发消息...",
                                fontSize = 14.sp,
                                color = Color(0x73FFFFFF)
                            )
                        }
                        BasicTextField(
                            value = inputDraft,
                            onValueChange = { inputDraft = it },
                            textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Sparkle AI Inspiration Button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0x1FFFFFFF))
                            .clickable { showInspirationSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✨", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Send Action Button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (inputDraft.isNotBlank()) Color(0xFFF5B041) else Color(0x1FFFFFFF))
                            .clickable {
                                if (inputDraft.isNotBlank() && !isAiGenerating) {
                                    val userText = inputDraft.trim()
                                    inputDraft = ""
                                    isAiGenerating = true
                                    liveActionText = "（正在沉思与审视……）"
                                    liveDialogueText = "..."
                                    Toast.makeText(context, "已发送：“$userText”", Toast.LENGTH_SHORT).show()

                                    onSendMessage(currentChar.id, userText) { act, dia ->
                                        isAiGenerating = false
                                        liveActionText = act
                                        liveDialogueText = dia
                                    }
                                } else if (inputDraft.isBlank()) {
                                    Toast.makeText(context, "请输入你想对${currentChar.name}说的话", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (inputDraft.isNotBlank()) "➤" else "+",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (inputDraft.isNotBlank()) Color.Black else Color.White
                        )
                    }
                }
            }
        }

        // --- GLOBAL PERSONA SETTINGS DIALOG (lines 199-259 of index.html) ---
        if (showPersonaSheet) {
            PersonaSettingsDialog(
                onDismiss = { showPersonaSheet = false },
                onSave = { nickname, gender, bio ->
                    showPersonaSheet = false
                    Toast.makeText(context, "全局设定已保存：$nickname ($gender)", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // --- AI INSPIRATION SHEET ---
        if (showInspirationSheet) {
            InspirationDialog(
                characterName = currentChar.name,
                onDismiss = { showInspirationSheet = false },
                onSelect = { inspiration ->
                    inputDraft = inspiration
                    showInspirationSheet = false
                }
            )
        }
    }
}

@Composable
fun PersonaSettingsDialog(
    onDismiss: () -> Unit,
    onSave: (nickname: String, gender: String, bio: String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf("女") }
    var bio by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF14151B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("‹ 返回", color = Color(0x99FFFFFF)) }
                    Text("全局设定", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.width(48.dp))
                }

                // 称呼
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("称呼", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        placeholder = { Text("智能体对我的称呼", fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFF5B041),
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 性别
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("性别", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("男", "女", "暂不透露").forEach { g ->
                            val isSelected = g == selectedGender
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFF5B041) else Color(0x1FFFFFFF))
                                    .clickable { selectedGender = g }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = g,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                // 我是谁
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("我是谁", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { bio = it },
                        placeholder = { Text("描述你所扮演角色的性格特征和事实设定，默认用于所有智能体聊天", fontSize = 12.sp) },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFF5B041),
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                }

                // 保存
                Button(
                    onClick = { onSave(nickname, selectedGender, bio) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5B041)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("保存设定", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun InspirationDialog(
    characterName: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val suggestions = listOf(
        "“你刚刚说的……究竟是什么意思？”",
        "（轻轻垂眸，掩饰内心的局促）“今天的事情，多谢你。”",
        "“如果我偏要留下来，你又能拿我怎样？”",
        "（向前迈近半步，直视他的眼眸）“我想知道更多关于你的过去。”",
        "“天色已经很晚了，你还不打算让我离开吗？”"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF14151B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💡 对话灵感 · $characterName", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Text("✕", color = Color(0x99FFFFFF), fontSize = 14.sp)
                    }
                }
                Text("点击即可填入对话框：", fontSize = 12.sp, color = Color(0x80FFFFFF))

                suggestions.forEach { sug ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x1FFFFFFF))
                            .clickable { onSelect(sug) }
                            .padding(12.dp)
                    ) {
                        Text(sug, fontSize = 13.sp, color = Color.White, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
