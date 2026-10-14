package com.character.app.characters

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.domain.Conversation
import com.character.app.domain.Note
import com.character.app.domain.UserProfile

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
    val segmentTabs = listOf("聊天", "图库", "开始创作")
    var selectedSegment by remember { mutableStateOf("聊天") }

    val filterPills = listOf("最近聊天", "已点赞", "我的")
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
        // 1. Header with Universal Top Bar + Segment Tabs + Search (NO "(c.ai)")
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Universal Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "消息互动",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x14FFFFFF))
                            .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙️", fontSize = 15.sp)
                    }
                }

                // Top Segment Bar (聊天 / 图库 / 开始创作)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    segmentTabs.forEach { tab ->
                        val isSelected = tab == selectedSegment
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0x26FFFFFF) else Color(0x0FFFFFFF))
                                .border(1.dp, if (isSelected) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedSegment = tab
                                    if (tab == "开始创作") onNewCharacter()
                                    else if (tab == "图库") Toast.makeText(context, "图库功能正在同步中...", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color(0x80FFFFFF)
                            )
                        }
                    }
                }

                // Search Capsule Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xC01A1B22))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索角色与对话历史",
                                fontSize = 13.sp,
                                color = Color(0x73FFFFFF)
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "✕",
                            fontSize = 13.sp,
                            color = Color(0x80FFFFFF),
                            modifier = Modifier.clickable { searchQuery = "" }
                        )
                    }
                }

                // Filter Pills (最近聊天 / 已点赞 / 我的)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    filterPills.forEach { pill ->
                        val isSelected = pill == selectedPill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color.White else Color(0x1AFFFFFF))
                                .clickable { selectedPill = pill }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = pill,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color(0xB3FFFFFF)
                            )
                        }
                    }
                }
            }
        }

        // 2. Character Conversation List (Real artwork avatars, status dots, badges, and dialogue quotes)
        items(plazaList) { charItem ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF14151B))
                    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                    .clickable { onOpenPlazaCharacter(charItem) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Avatar with green status dot
                Box(modifier = Modifier.size(50.dp)) {
                    Image(
                        painter = painterResource(charItem.avatarResId),
                        contentDescription = charItem.name,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color(0x26FFFFFF), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF52C41A))
                            .border(2.dp, Color(0xFF14151B), CircleShape)
                            .align(Alignment.BottomEnd)
                    )
                }

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = charItem.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(charItem.badgeColorHex).copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                            ) {
                                Text(
                                    text = charItem.badgeCategory,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(charItem.badgeColorHex)
                                )
                            }
                        }
                        Text(
                            text = "刚刚",
                            fontSize = 10.5.sp,
                            color = Color(0x66FFFFFF)
                        )
                    }

                    Text(
                        text = charItem.quote,
                        fontSize = 12.sp,
                        color = Color(0x80FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
