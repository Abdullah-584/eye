package com.familyguard.core.domain.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleEvaluatorTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val karachi = ZoneId.of("Asia/Karachi")

    private val bedtime = ScheduleView(
        name = "Bedtime",
        daysMask = 0b0111111, // Sun..Sat all except Saturday? bit0=Sun..bit6=Sat: 0b0111111 = Sun-Fri
        startTime = "21:00",
        endTime = "07:00",
    )

    @Test
    fun `day mask bits map ISO days correctly`() {
        // ISO: 1=Mon … 7=Sun. Bit = iso % 7 (Sun -> 0).
        val mondayOnly = 0b0000010
        assertTrue(ScheduleEvaluator.dayEnabled(mondayOnly, 1))
        assertFalse(ScheduleEvaluator.dayEnabled(mondayOnly, 2))
        val sundayOnly = 0b0000001
        assertTrue(ScheduleEvaluator.dayEnabled(sundayOnly, 7))
    }

    @Test
    fun `bedtime is active inside the evening window`() {
        // 2026-03-09 is a Monday.
        val at = Instant.parse("2026-03-09T18:30:00Z") // 23:30 PKT Monday
        assertTrue(ScheduleEvaluator.isActive(bedtime, at, karachi))
    }

    @Test
    fun `bedtime stays active after midnight because the start day was enabled`() {
        // 2026-03-09T20:00Z = 2026-03-10 01:00 PKT (Tuesday). The window that
        // is running started Monday 21:00, and Monday is enabled.
        val at = Instant.parse("2026-03-09T20:00:00Z")
        assertTrue(ScheduleEvaluator.isActive(bedtime, at, karachi))
    }

    @Test
    fun `bedtime releases at seven in the morning local`() {
        // 2026-03-09T20:30Z = 2026-03-10 01:30 PKT — still inside (before 07:00).
        assertTrue(ScheduleEvaluator.isActive(bedtime, Instant.parse("2026-03-09T20:30:00Z"), karachi))
        // 2026-03-09T02:30Z = 07:30 PKT Tuesday — outside.
        assertFalse(ScheduleEvaluator.isActive(bedtime, Instant.parse("2026-03-09T02:30:00Z"), karachi))
    }

    @Test
    fun `disabled schedules never activate`() {
        val off = bedtime.copy(enabled = false)
        assertFalse(ScheduleEvaluator.isActive(off, Instant.parse("2026-03-09T18:30:00Z"), karachi))
    }

    @Test
    fun `same day window works`() {
        val school = ScheduleView("School", 0b0011110, "08:00", "16:00") // Mon-Fri
        // 2026-03-09 (Mon) 12:00 UTC = 13:00 PKT → inside.
        assertTrue(ScheduleEvaluator.isActive(school, Instant.parse("2026-03-09T09:00:00Z"), karachi))
        // Sunday 2026-03-08 12:00 PKT → day disabled.
        assertFalse(ScheduleEvaluator.isActive(school, Instant.parse("2026-03-08T07:00:00Z"), karachi))
    }

    @Test
    fun `DST spring forward does not double run or skip the window`() {
        // Europe/Berlin springs forward 2026-03-29 02:00 → 03:00 (that Sunday).
        // 01:30 CET (00:30Z) is inside the 01:00–03:00 window…
        val shift = ScheduleView("Shift", 0b0111111, "01:00", "03:00")
        assertTrue(ScheduleEvaluator.isActive(shift, Instant.parse("2026-03-29T00:30:00Z"), berlin))
        // …and 04:30 CEST (02:30Z) — after the jump — is outside it.
        assertFalse(ScheduleEvaluator.isActive(shift, Instant.parse("2026-03-29T02:30:00Z"), berlin))
    }

    @Test
    fun `nextActivation returns the next enabled start`() {
        // From Monday noon PKT, next 21:00 is Monday evening.
        val from = Instant.parse("2026-03-09T07:00:00Z") // 12:00 PKT Monday
        val next = requireNotNull(ScheduleEvaluator.nextActivation(bedtime, from, karachi))
        val nextLocal = next.atZone(karachi)
        assertEquals(21, nextLocal.hour)
        assertEquals(9, nextLocal.dayOfMonth)
    }
}
