package com.character.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.character.app.characters.CharacterEditorScreen
import com.character.app.characters.CharactersScreen
import com.character.app.characters.ExploreScreen
import com.character.app.characters.HomeScreen
import com.character.app.characters.MessagesScreen
import com.character.app.characters.PLAZA_CHARACTERS
import com.character.app.characters.PlazaScreen
import com.character.app.designsystem.CharacterTheme
import com.character.app.notes.NotesScreen
import com.character.app.profile.ProfileEditorScreen
import com.character.app.profile.ProfileScreen
import com.character.app.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ClientViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            var activePlazaCharId by remember { mutableStateOf<String?>("huosijue") }

            LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground(true) }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.foreground(false) }

            CharacterTheme(dark = true) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }

                // Back handlers
                BackHandler(state.characterEditor != null) { model.closeCharacterEditor() }
                BackHandler(state.profileEditor != null) { model.closeProfileEditor() }
                BackHandler(state.tab != "首页") { model.tab("首页") }

                Scaffold(
                    containerColor = Color(0xFF0C0D11),
                    topBar = {
                        Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                            if (state.tab == "设置") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TextButton(onClick = { model.tab("首页") }) {
                                        Text("‹ 返回首页", color = Color(0xFFF5B041))
                                    }
                                    Text(
                                        text = "系统设置",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            if (state.busy) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFFF5B041),
                                    trackColor = Color(0x33F5B041)
                                )
                            }
                            state.error?.let { error ->
                                Surface(
                                    color = Color(0xFF2C1618),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(error, color = Color(0xFFFF7875), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = model::clearError) {
                                            Text("关闭", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (state.characterEditor == null && state.profileEditor == null && state.tab != "设置") {
                            H5GlassBottomBar(
                                currentTab = state.tab,
                                onSelectTab = { selected ->
                                    if (selected == "创作") model.newCharacter()
                                    else model.tab(selected)
                                }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (state.profileEditor != null) {
                            ProfileEditorScreen(
                                checkNotNull(state.profileEditor), state.pendingProfileChange, state.busy,
                                model::changeProfile, model::saveProfile, model::retryProfile, model::reloadProfile, model::closeProfileEditor
                            )
                        } else if (state.characterEditor != null) {
                            CharacterEditorScreen(
                                checkNotNull(state.characterEditor), state.busy, state.pendingCharacter != null,
                                model::changeCharacter, model::saveCharacter, model::reloadCharacter, model::closeCharacterEditor
                            )
                        } else {
                            when (state.tab) {
                                "首页" -> {
                                    HomeScreen(
                                        conversations = state.conversations,
                                        characters = state.characters,
                                        onOpenConversation = model::open,
                                        onStartWithCharacter = { plaza ->
                                            activePlazaCharId = plaza.id
                                            model.startPlazaCharacter(plaza)
                                        },
                                        onOpenPlaza = { model.tab("广场") },
                                        onOpenExplore = { model.tab("实验玩法") },
                                        onOpenProfile = { model.tab("我的") },
                                        onOpenSettings = { model.tab("设置") },
                                        activeCharacterId = activePlazaCharId,
                                        latestAssistantMessage = state.messages.lastOrNull { it.role == "assistant" }?.text,
                                        onSendMessage = { charId, text, _ ->
                                            val plaza = PLAZA_CHARACTERS.find { it.id == charId } ?: PLAZA_CHARACTERS.first()
                                            model.sendPlazaDirectMessage(plaza, text)
                                        }
                                    )
                                }
                                "广场" -> {
                                    PlazaScreen(
                                        onSelectCharacter = { plaza ->
                                            activePlazaCharId = plaza.id
                                            model.startPlazaCharacter(plaza)
                                            model.tab("首页")
                                        },
                                        onOpenSettings = { model.tab("设置") }
                                    )
                                }
                                "实验玩法" -> {
                                    ExploreScreen(
                                        onSelectDrama = { charId ->
                                            val plaza = PLAZA_CHARACTERS.find { it.id == charId } ?: PLAZA_CHARACTERS.first()
                                            activePlazaCharId = plaza.id
                                            model.startPlazaCharacter(plaza)
                                            model.tab("首页")
                                        },
                                        onOpenSettings = { model.tab("设置") }
                                    )
                                }
                                "消息" -> {
                                    MessagesScreen(
                                        conversations = state.conversations,
                                        notes = state.notes,
                                        userProfile = state.userProfile,
                                        busy = state.busy,
                                        onOpenConversation = { conv ->
                                            model.open(conv)
                                            val char = state.characters.find { it.id == conv.characterId }
                                            val plaza = PLAZA_CHARACTERS.find { it.name == char?.name || it.name == conv.title }
                                            if (plaza != null) activePlazaCharId = plaza.id
                                            model.tab("首页")
                                        },
                                        onOpenPlazaCharacter = { plaza ->
                                            activePlazaCharId = plaza.id
                                            model.startPlazaCharacter(plaza)
                                            model.tab("首页")
                                        },
                                        onRefresh = model::refreshHome,
                                        onNewCharacter = model::newCharacter,
                                        onEditProfile = model::editProfile,
                                        onOpenSettings = { model.tab("设置") }
                                    )
                                }
                                "角色" -> {
                                    CharactersScreen(
                                        state.characters, state.conversations, state.busy, model::create, model::open, model::refreshHome,
                                        state.canManageCharacters, state.pendingCharacter != null, model::newCharacter, model::editCharacter, model::deleteCharacter
                                    )
                                }
                                "笔记" -> {
                                    NotesScreen(state.notes, state.busy, model::refreshNotes)
                                }
                                "我的" -> {
                                    ProfileScreen(
                                        state.userProfile, state.pendingProfileChange, state.canManageProfile, state.busy,
                                        model::refreshProfile, model::editProfile, model::resetProfile, model::retryProfile, model::reloadProfile
                                    )
                                }
                                else -> {
                                    SettingsScreen(
                                        state.url, state.connected, state.dark, state.busy, state.polling,
                                        model::connect, model::disconnect, model::theme, model::mode
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 1:1 Pixel-Perfect Recreation of H5 Glassmorphic 5-Key Bottom Navigation Bar
 * (首页, 广场, [+ 创作], 实验玩法, 消息)
 */
@Composable
fun H5GlassBottomBar(
    currentTab: String,
    onSelectTab: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF0090A0D))
            .border(1.dp, Color(0x14FFFFFF))
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 首页
        H5TabItem(
            label = "首页",
            icon = "🏛️",
            isActive = currentTab == "首页",
            onClick = { onSelectTab("首页") }
        )

        // 2. 广场
        H5TabItem(
            label = "广场",
            icon = "🧭",
            isActive = currentTab == "广场",
            onClick = { onSelectTab("广场") }
        )

        // 3. 创作 (Center Elevated Button)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clickable { onSelectTab("创作") }
                .padding(horizontal = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5B041)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "＋",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            Text(
                text = "创作",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // 4. 实验玩法
        H5TabItem(
            label = "实验玩法",
            icon = "🎭",
            isActive = currentTab == "实验玩法",
            onClick = { onSelectTab("实验玩法") }
        )

        // 5. 消息
        H5TabItem(
            label = "消息",
            icon = "💬",
            isActive = currentTab == "消息",
            onClick = { onSelectTab("消息") }
        )
    }
}

@Composable
fun H5TabItem(
    label: String,
    icon: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = icon,
            fontSize = 17.sp,
            color = if (isActive) Color.White else Color(0x66FFFFFF)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) Color.White else Color(0x66FFFFFF)
        )
    }
}
