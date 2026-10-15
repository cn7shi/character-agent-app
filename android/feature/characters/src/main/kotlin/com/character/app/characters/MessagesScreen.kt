package com.character.app.characters

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.character.app.characters.components.ConversationItemRow
import com.character.app.characters.components.MessagesHeader
import com.character.app.domain.Conversation
import com.character.app.domain.Note
import com.character.app.domain.UserProfile

/**
 * MessagesScreen
 *
 * Messages and recent conversation list screen.
 * Refactored into a lean orchestrator (~75 lines) using atomic components.
 */
@Composable
fun MessagesScreen(
    conversations: List<Conversation>,
    notes: List<Note>,
    userProfile: UserProfile?,
    busy: Boolean,
    onOpenConversation: (Conversation) -> Unit,
    onOpenPlazaCharacter: (PlazaCharacter) -> Unit = {},
    onRefresh: () -> Unit,
    onNewCharacter: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val segmentTabs = remember { listOf("聊天", "图库", "开始创作") }
    var selectedSegment by remember { mutableStateOf("聊天") }

    val filterPills = remember { listOf("最近聊天", "已点赞", "我的") }
    var selectedPill by remember { mutableStateOf("最近聊天") }
    var searchQuery by remember { mutableStateOf("") }

    val plazaList = remember(searchQuery, selectedPill) {
        PLAZA_CHARACTERS.filter {
            searchQuery.isBlank() ||
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.quote.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D11))
            .testTag("messages-screen"),
        contentPadding = PaddingValues(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Header with Universal Top Bar + Segment Tabs + Search + Filter Pills
        item {
            MessagesHeader(
                segmentTabs = segmentTabs,
                selectedSegment = selectedSegment,
                onSelectSegment = { tab ->
                    selectedSegment = tab
                    if (tab == "开始创作") onNewCharacter()
                    else if (tab == "图库") Toast.makeText(context, "图库功能正在同步中...", Toast.LENGTH_SHORT).show()
                },
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                filterPills = filterPills,
                selectedPill = selectedPill,
                onSelectPill = { selectedPill = it },
                onOpenSettings = onOpenSettings
            )
        }

        // 2. Character Conversation List
        items(plazaList) { charItem ->
            ConversationItemRow(
                character = charItem,
                onClick = { onOpenPlazaCharacter(charItem) }
            )
        }
    }
}
