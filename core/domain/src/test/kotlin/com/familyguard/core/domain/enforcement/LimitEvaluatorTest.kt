package com.familyguard.core.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LimitEvaluatorTest {

    private val zone = ZoneId.of("Asia/Karachi")
    private val noon = Instant.parse("2026-03-10T12:00:00Z") // 17:00 local

    @Test
    fun `remaining seconds never go negative`() {
        assertEquals(500L, LimitEvaluator.secondsLeft(1000, 500))
        assertEquals(0L, LimitEvaluator.secondsLeft(1000, 1000))
        assertEquals(0L, LimitEvaluator.secondsLeft(1000, 1500))
    }

    @Test
    fun `blocks once used reaches the limit`() {
        val limit = AppLimitView("com.example.game", dailyLimitSeconds = 120)
        assertTrue(LimitEvaluator.shouldBlock(limit, 120, noon, zone))
        assertTrue(LimitEvaluator.shouldBlock(limit, 118, noon, zone, graceSeconds = 3))
        assertFalse(LimitEvaluator.shouldBlock(limit, 100, noon, zone))
    }

    @Test
    fun `disabled limits never block`() {
        val limit = AppLimitView("com.example.game", dailyLimitSeconds = 120, enabled = false)
        assertFalse(LimitEvaluator.shouldBlock(limit, 9999, noon, zone))
    }

    @Test
    fun `grace window absorbs coarse usage granularity`() {
        val limit = AppLimitView("com.example.game", dailyLimitSeconds = 120)
        // 3 s of grace: 118 + 3 >= 120 blocks, 116 does not.
        assertTrue(LimitEvaluator.shouldBlock(limit, 118, noon, zone, graceSeconds = 3))
        assertFalse(LimitEvaluator.shouldBlock(limit, 116, noon, zone, graceSeconds = 3))
    }

    @Test
    fun `limit scoped to another day does not apply`() {
        val limit = AppLimitView(
            "com.example.game",
            dailyLimitSeconds = 120,
            limitDay = LocalDate.parse("2026-03-09"),
        )
        assertFalse(LimitEvaluator.shouldBlock(limit, 9999, noon, zone))
    }

    @Test
    fun `total remaining sums only enabled limits`() {
        val limits = listOf(
            AppLimitView("a", 100),
            AppLimitView("b", 200),
            AppLimitView("c", 300, enabled = false),
        )
        val used = mapOf("a" to 40L, "b" to 50L, "c" to 0L)
        assertEquals(210L, LimitEvaluator.totalRemaining(limits, used))
    }
}
