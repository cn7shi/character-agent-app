package com.character.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.character.app.characters.CharactersScreen
import com.character.app.chat.ChatScreen
import com.character.app.designsystem.CharacterTheme
import com.character.app.notes.NotesScreen
import com.character.app.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ClientViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground(true) }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.foreground(false) }
            CharacterTheme(state.dark) {
                BackHandler(state.selected != null) { model.back() }
                Scaffold(
                    topBar = {
                        Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.selected != null) TextButton(onClick = model::back) { Text("‹ 返回") }
                                Text(state.selected?.title ?: "角色 · 开发版", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                            }
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.error?.let { error ->
                                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(error, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                                        if (state.selected != null) TextButton(onClick = { model.clearError(); model.reconnectChat() }) { Text("重新连接") }
                                        TextButton(onClick = model::clearError) { Text("关闭") }
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (state.selected == null) NavigationBar {
                            listOf("角色" to "◉", "笔记" to "▤", "设置" to "⚙").forEach { (tab, icon) ->
                                NavigationBarItem(selected = state.tab == tab, enabled = state.connected || tab == "设置",
                                    onClick = { model.tab(tab) }, icon = { Text(icon) }, label = { Text(tab) })
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (state.selected != null) ChatScreen(state.messages, state.pending, state.runs, state.draft, state.busy,
                            model::draft, model::send, model::cancel, model::retry, model::dismiss)
                        else when (state.tab) {
                            "角色" -> CharactersScreen(state.characters, state.conversations, state.busy, model::create, model::open, model::refreshHome)
                            "笔记" -> NotesScreen(state.notes, state.busy, model::refreshNotes)
                            else -> SettingsScreen(state.url, state.connected, state.dark, state.busy, state.polling, model::connect, model::disconnect, model::theme, model::mode)
                        }
                    }
                }
            }
        }
    }
}
