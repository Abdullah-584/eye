package com.familyguard.core.domain.enforcement

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedule (bedtime / school-time) evaluation. Pure: all time comes from the
 * caller so DST and midnight-crossing cases are unit-testable (PLAN §12.1).
 */
object ScheduleEvaluator {

    /** True when [dayOfWeekIso] (1 = Monday … 7 = Sunday) is ticked in [daysMask] (bit 0 = Sunday). */
    fun dayEnabled(daysMask: Int, dayOfWeekIso: Int): Boolean {
        val bit = (dayOfWeekIso % 7) // ISO 7 (Sun) -> bit 0; 1 (Mon) -> bit 1
        return (daysMask shr bit) and 1 == 1
    }

    /**
     * Whether the schedule is active at [now] in [zone].
     * Handles windows that cross midnight: 21:00–07:00 is active at 23:30 on
     * a enabled day, and also at 06:00 the next morning (the *start* day is
     * the one that must be enabled).
     */
    fun isActive(schedule: ScheduleView, now: Instant, zone: ZoneId): Boolean {
        if (!schedule.enabled) return false
        val localDateTime = now.atZone(zone).toLocalDateTime()
        val day = localDateTime.toLocalDate()
        val time = localDateTime.toLocalTime()
        val start = parseTime(schedule.startTime) ?: return false
        val end = parseTime(schedule.endTime) ?: return false

        return if (start < end) {
            // Same-day window.
            dayEnabled(schedule.daysMask, day.dayOfWeek.value) && time >= start && time < end
        } else {
            // Crosses midnight: part A today (start..24:00), part B after
            // midnight (00:00..end) which belongs to the *previous* day's window.
            val todayEnabled = dayEnabled(schedule.daysMask, day.dayOfWeek.value)
            val yesterdayEnabled = dayEnabled(schedule.daysMask, day.minusDays(1).dayOfWeek.value)
            (todayEnabled && time >= start) || (yesterdayEnabled && time < end)
        }
    }

    /** Next moment the schedule turns active, or null if no day matches within 8 days. */
    fun nextActivation(schedule: ScheduleView, from: Instant, zone: ZoneId, horizonDays: Int = 8): Instant? {
        val start = parseTime(schedule.startTime) ?: return null
        // Walk from *the given instant's* local day — never wall-clock now()
        // (PLAN §12.1: fake clock only).
        val firstDay = from.atZone(zone).toLocalDate()
        for (offset in 0..horizonDays) {
            val day = firstDay.plusDays(offset.toLong())
            if (!dayEnabled(schedule.daysMask, day.dayOfWeek.value)) continue
            val candidate = day.atTime(start).atZone(zone).toInstant()
            if (candidate >= from) return candidate
        }
        return null
    }

    fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()
}

data class ScheduleView(
    val name: String,
    val daysMask: Int,
    val startTime: String,
    val endTime: String,
    val enabled: Boolean = true,
)
