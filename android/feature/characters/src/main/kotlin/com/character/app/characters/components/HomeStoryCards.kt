package com.character.app.characters.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.character.app.characters.VoicePlayer

/**
 * HomeStoryCards
 *
 * Dedicated component for Synopsis card and interactive Dialogue Card with Floating Voice Pill.
 * Extracted from HomeScreen to enforce separation of concerns and eliminate nested indentation.
 */
@Composable
fun HomeStoryCards(
    synopsis: String,
    actionText: String,
    dialogueText: String,
    characterName: String,
    voiceDuration: Int,
    onAdvanceScript: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val isVoicePlaying = VoicePlayer.isPlaying.value
    val isVoiceLoading = VoicePlayer.isLoading.value
    val voiceCountdown = VoicePlayer.currentCountdown.value
    val totalVoiceDuration = VoicePlayer.totalDuration.value

    Column(
        modifier = modifier
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
                    text = synopsis,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = Color(0xCCFFFFFF),
                    maxLines = 3
                )
            }
        }

        // 2. Dialogue Card with Floating Voice Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            // Dialogue Card Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xCC14151A))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(18.dp))
                    .clickable { onAdvanceScript() }
                    .padding(top = 22.dp, start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Action / Narrative text in italics
                Text(
                    text = actionText,
                    fontSize = 13.sp,
                    lineHeight = 19.5.sp,
                    color = Color(0xB3FFFFFF)
                )

                // Spoken Dialogue in bold white text
                Text(
                    text = dialogueText,
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
                        val fullText = if (actionText.isNotBlank()) "（$actionText）$dialogueText" else dialogueText
                        VoicePlayer.togglePlay(
                            context = context,
                            characterName = characterName,
                            rawText = fullText
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = when {
                        isVoiceLoading -> "⏳"
                        isVoicePlaying -> "⏸"
                        else -> "▶"
                    },
                    fontSize = 10.sp,
                    color = Color.White
                )
                // 4 Animated Wave bars
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
                    text = when {
                        isVoiceLoading -> "生成中"
                        isVoicePlaying -> "${voiceCountdown}\""
                        else -> "${totalVoiceDuration.takeIf { it > 0 } ?: voiceDuration}\""
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
