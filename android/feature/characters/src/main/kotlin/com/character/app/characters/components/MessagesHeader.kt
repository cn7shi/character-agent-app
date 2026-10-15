package com.character.app.characters.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MessagesHeader
 *
 * Sticky header for MessagesScreen containing universal nav, segment tabs, search capsule,
 * and filter pills.
 */
@Composable
fun MessagesHeader(
    segmentTabs: List<String>,
    selectedSegment: String,
    onSelectSegment: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filterPills: List<String>,
    selectedPill: String,
    onSelectPill: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
                        .border(
                            1.dp,
                            if (isSelected) Color(0x33FFFFFF) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectSegment(tab) }
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
                    onValueChange = onSearchQueryChange,
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
                    modifier = Modifier.clickable { onSearchQueryChange("") }
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
                        .clickable { onSelectPill(pill) }
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
