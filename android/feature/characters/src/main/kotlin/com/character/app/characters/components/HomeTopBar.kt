package com.character.app.characters.components

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.characters.PlazaCharacter

/**
 * HomeTopBar
 *
 * Dedicated top navigation and character status header component:
 * - Profile pill (avatar, name, likes count, follow button)
 * - Comment pill
 * - Search glass button & Profile shortcut button
 * - Music pill & Persona settings shortcut
 */
@Composable
fun HomeTopBar(
    currentChar: PlazaCharacter,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
    onCycleCharacter: () -> Unit,
    onOpenPlaza: () -> Unit,
    onOpenPersona: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // --- Top Header Row ---
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
                        .clickable { onCycleCharacter() }
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
                            .clickable { onToggleFollow() },
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
                        .clickable { onOpenPersona() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 16.sp)
                }
            }
        }

        // --- Music Bar & Persona Shortcut ---
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
                    .clickable { onOpenPersona() },
                contentAlignment = Alignment.Center
            ) {
                Text("⚙️", fontSize = 12.sp)
            }
        }
    }
}
