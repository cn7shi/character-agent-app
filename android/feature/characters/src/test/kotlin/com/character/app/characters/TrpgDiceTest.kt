package com.character.app.characters

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TrpgDiceTest {

    @Test
    fun criticalSuccessOn20() {
        val result = TrpgDice.evaluate(20)
        assertEquals(20, result.roll)
        assertTrue(result.isSuccess)
        assertTrue(result.feedback.contains("大成功"))
    }

    @Test
    fun regularSuccessBetween15And19() {
        val result = TrpgDice.evaluate(15)
        assertEquals(15, result.roll)
        assertTrue(result.isSuccess)
        assertTrue(result.feedback.contains("成功！"))
    }

    @Test
    fun normalPassBetween10And14() {
        val result = TrpgDice.evaluate(10)
        assertEquals(10, result.roll)
        assertTrue(result.isSuccess)
        assertTrue(result.feedback.contains("普通通过"))
    }

    @Test
    fun criticalFailureOn1() {
        val result = TrpgDice.evaluate(1)
        assertEquals(1, result.roll)
        assertFalse(result.isSuccess)
        assertTrue(result.feedback.contains("大失败"))
    }

    @Test
    fun regularFailureBetween2And9() {
        val result = TrpgDice.evaluate(5)
        assertEquals(5, result.roll)
        assertFalse(result.isSuccess)
        assertTrue(result.feedback.contains("检定失败"))
    }

    @Test
    fun randomRollProducesValidRange() {
        val fixedRandom = Random(42)
        val result = TrpgDice.roll(fixedRandom)
        assertTrue(result.roll in 1..20)
        assertNotNull(result.feedback)
    }
}
