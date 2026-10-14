package com.character.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

val PRESET_AVATARS = listOf(
    "🎒" to "旅行者",
    "📖" to "故事家",
    "🦊" to "灵动狐",
    "🌸" to "治愈少女",
    "☕" to "咖啡师",
    "🌙" to "守夜人",
    "🐱" to "小猫咪",
    "⚔️" to "守护骑士",
    "🎨" to "艺术家",
    "🤖" to "仿生人"
)

fun getAvatarEmoji(name: String, avatarId: String? = null): String {
    if (!avatarId.isNullOrBlank()) {
        val found = PRESET_AVATARS.find { it.first == avatarId }
        if (found != null) return found.first
    }
    if (name.contains("林舟")) return "🎒"
    if (name.contains("星禾")) return "📖"
    if (name.contains("霍司爵")) return "👑"
    if (name.contains("萧寒")) return "⚔️"
    if (name.contains("江野")) return "🏎️"
    if (name.contains("严烈")) return "🍷"
    if (name.contains("沈言")) return "📚"
    if (name.contains("希尔达")) return "🖤"
    val index = abs(name.hashCode()) % PRESET_AVATARS.size
    return PRESET_AVATARS[index].first
}

private val AVATAR_COLORS = listOf(
    Color(0xFF384B66),
    Color(0xFF5A4168),
    Color(0xFF395C48),
    Color(0xFF6B4D3B),
    Color(0xFF6B3B52),
    Color(0xFF3B5D60)
)

@Composable
fun CharacterAvatar(
    name: String,
    avatarId: String? = null,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val emoji = getAvatarEmoji(name, avatarId)
    val colorIndex = abs(name.hashCode()) % AVATAR_COLORS.size
    val bgColor = AVATAR_COLORS[colorIndex]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = (size.value * 0.52f).sp
        )
    }
}
