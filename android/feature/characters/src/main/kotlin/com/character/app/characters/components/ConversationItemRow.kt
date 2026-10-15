package com.character.app.characters.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.characters.PlazaCharacter

/**
 * ConversationItemRow
 *
 * Rich conversation list row with character avatar, online status indicator, category badge,
 * and latest dialogue quote preview.
 */
@Composable
fun ConversationItemRow(
    character: PlazaCharacter,
    onClick: () -> Unit,
    timeText: String = "刚刚",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF14151B))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar with online status indicator dot
        Box(modifier = Modifier.size(50.dp)) {
            Image(
                painter = painterResource(character.avatarResId),
                contentDescription = character.name,
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

        // Character Name, Badges, and Quote Snippet
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
                        text = character.name,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(character.badgeColorHex).copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = character.badgeCategory,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(character.badgeColorHex)
                        )
                    }
                }
                Text(
                    text = timeText,
                    fontSize = 10.5.sp,
                    color = Color(0x66FFFFFF)
                )
            }

            Text(
                text = character.quote,
                fontSize = 12.sp,
                color = Color(0x80FFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
