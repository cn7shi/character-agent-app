package com.character.app.characters

import kotlin.random.Random

/**
 * TrpgDice
 *
 * Domain helper for TRPG D20 dice checks. Decouples game rules from UI composables.
 */
object TrpgDice {
    data class RollResult(
        val roll: Int,
        val feedback: String,
        val isSuccess: Boolean
    )

    fun evaluate(roll: Int): RollResult {
        val clamped = roll.coerceIn(1, 20)
        val feedback = when {
            clamped == 20 -> "大成功！(D20: 20) 突破极值，完美达成行动！"
            clamped >= 15 -> "成功！(D20: $clamped) 行动顺利，触发隐藏剧情。"
            clamped >= 10 -> "普通通过。(D20: $clamped) 艰难避开危险。"
            clamped == 1 -> "大失败！(D20: 1) 触发致命反转！"
            else -> "检定失败。(D20: $clamped) 局势进一步恶化！"
        }
        val isSuccess = clamped >= 10
        return RollResult(clamped, feedback, isSuccess)
    }

    fun roll(random: Random = Random.Default): RollResult {
        return evaluate(random.nextInt(1, 21))
    }
}
