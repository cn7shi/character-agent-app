package com.character.app.characters

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.character.app.characters.components.ExploreDramaSection
import com.character.app.characters.components.ExploreGamesSection
import com.character.app.characters.components.ExploreHeader
import com.character.app.characters.components.ExploreHeroBanner

/**
 * ExploreScreen
 *
 * Experimental gameplays and drama immersion screen.
 * Refactored into a lean, readable orchestrator (~75 lines) composed of atomic sections.
 */
@Composable
fun ExploreScreen(
    onSelectDrama: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tabs = remember { listOf("精选", "交互短剧", "互动游戏", "声音FM", "共鸣空间") }
    var selectedTab by remember { mutableStateOf("精选") }

    // State for interactive TRPG dice game
    var diceResult by remember { mutableStateOf<Int?>(null) }
    var diceFeedback by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D11))
            .testTag("explore-screen"),
        contentPadding = PaddingValues(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header with Title & Filter Tabs
        item {
            ExploreHeader(
                tabs = tabs,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onOpenSettings = onOpenSettings
            )
        }

        // 2. Featured Hero Drama Banner Card (霍司爵)
        item {
            ExploreHeroBanner(
                onSelectDrama = onSelectDrama
            )
        }

        // 3. Section: 交互短剧
        item {
            ExploreDramaSection(
                onSelectDrama = onSelectDrama
            )
        }

        // 4. Section: 互动游戏 (TRPG Dice & Undercover)
        item {
            ExploreGamesSection(
                diceResult = diceResult,
                diceFeedback = diceFeedback,
                onRollDice = {
                    val result = TrpgDice.roll()
                    diceResult = result.roll
                    diceFeedback = result.feedback
                    Toast.makeText(context, result.feedback, Toast.LENGTH_SHORT).show()
                },
                onSelectUndercover = {
                    Toast.makeText(context, "正在开启「豪门暗涌」多角色群聊推理局...", Toast.LENGTH_SHORT).show()
                    onSelectDrama("huosijue")
                }
            )
        }
    }
}
