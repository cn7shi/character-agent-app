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
import androidx.compose.ui.unit.sp
import com.character.app.characters.components.ConversationItemRow
import com.character.app.characters.components.MessagesHeader
import com.character.app.characters.components.RenameConversationDialog
import com.character.app.domain.Character
import com.character.app.domain.Conversation
import com.character.app.domain.Note
import com.character.app.domain.UserProfile

/**
 * MessagesScreen
 *
 * Messages and recent conversation list screen.
 * Supports instant search filtering by conversation title and character name,
 * and conversation rename via dialog.
 */
@Composable
fun MessagesScreen(
    conversations: List<Conversation>,
    notes: List<Note>,
    userProfile: UserProfile?,
    busy: Boolean,
    onOpenConversation: (Conversation) -> Unit,
    characters: List<Character> = emptyList(),
    onOpenPlazaCharacter: (PlazaCharacter) -> Unit = {},
    onRenameConversation: (Conversation, String) -> Unit = { _, _ -> },
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
    var renamingConversation by remember { mutableStateOf<Conversation?>(null) }

    // Filter active conversations by search query (matching conversation title or character name)
    val filteredConversations = remember(conversations, characters, searchQuery) {
        conversations.filter { conv ->
            if (searchQuery.isBlank()) true
            else {
                val charEntity = characters.find { it.id == conv.characterId }
                val matchedChar = PLAZA_CHARACTERS.find { it.name == charEntity?.name || it.name == conv.title || it.id == conv.characterId }
                conv.title.contains(searchQuery, ignoreCase = true) ||
                        (charEntity?.name?.contains(searchQuery, ignoreCase = true) == true) ||
                        (matchedChar?.name?.contains(searchQuery, ignoreCase = true) == true) ||
                        (matchedChar?.badgeCategory?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

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

        // 2. Active Conversations List (if any exist)
        if (filteredConversations.isNotEmpty()) {
            items(filteredConversations, key = { it.id }) { conv ->
                val charEntity = characters.find { it.id == conv.characterId }
                val plazaChar = PLAZA_CHARACTERS.find { it.name == charEntity?.name || it.name == conv.title || it.id == conv.characterId }
                    ?: PLAZA_CHARACTERS.first()
                ConversationItemRow(
                    character = plazaChar,
                    title = conv.title,
                    subtitle = plazaChar.quote,
                    onClick = { onOpenConversation(conv) },
                    onRename = { renamingConversation = conv }
                )
            }
        } else if (conversations.isNotEmpty() && searchQuery.isNotBlank()) {
            // Search yielded no active conversation results
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.Text(
                        text = "未找到与“$searchQuery”相关的会话",
                        fontSize = 13.sp,
                        color = Color(0x66FFFFFF)
                    )
                }
            }
        } else {
            // Fallback: Display discoverable plaza characters
            items(plazaList, key = { it.id }) { charItem ->
                ConversationItemRow(
                    character = charItem,
                    onClick = { onOpenPlazaCharacter(charItem) }
                )
            }
        }
    }

    // Rename Conversation Dialog
    if (renamingConversation != null) {
        RenameConversationDialog(
            initialTitle = renamingConversation!!.title,
            onDismiss = { renamingConversation = null },
            onConfirm = { newTitle ->
                val target = renamingConversation!!
                onRenameConversation(target, newTitle)
                renamingConversation = null
                Toast.makeText(context, "会话已重命名为：$newTitle", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
