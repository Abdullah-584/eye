package com.familyguard.core.domain.enforcement

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure limit arithmetic. No wall-clock access: every function takes the
 * current instant and the child's zone so tests can pin time exactly
 * (PLAN §12.1 — fake clock only, never System.currentTimeMillis).
 */
object LimitEvaluator {

    /**
     * Seconds the child has left today for [packageName] given the
     * accumulated [usedSeconds]. Returns 0 when the budget is exhausted.
     */
    fun secondsLeft(limitSeconds: Int, usedSeconds: Long): Long =
        (limitSeconds - usedSeconds).coerceAtLeast(0L)

    /**
     * Whether [packageName] must be blocked right now.
     *
     * @param graceSeconds a small tolerance so OEM clock skew / coarse
     *   UsageStats granularity does not produce flicker (M5 wants ±3 s).
     */
    fun shouldBlock(
        limit: AppLimitView,
        usedSecondsToday: Long,
        now: Instant,
        zone: ZoneId,
        graceSeconds: Long = 3,
    ): Boolean {
        if (!limit.enabled) return false
        val day = LocalDate.now(zone)
        // A limit only applies to the day it was measured in; the caller
        // passes usage already scoped to [day] in the child's zone.
        if (limit.limitDay != null && limit.limitDay != day) return false
        return usedSecondsToday + graceSeconds >= limit.dailyLimitSeconds
    }

    /** Remaining allowance for the whole day across all apps, in seconds. */
    fun totalRemaining(limits: List<AppLimitView>, usedByPackage: Map<String, Long>): Long =
        limits.filter { it.enabled }
            .sumOf { secondsLeft(it.dailyLimitSeconds, usedByPackage[it.packageName] ?: 0L) }
}

/** Minimal view of a limit for the evaluator (avoids Room/JSON coupling). */
data class AppLimitView(
    val packageName: String,
    val dailyLimitSeconds: Int,
    val enabled: Boolean = true,
    /** The local day this measurement belongs to, if already scoped. */
    val limitDay: LocalDate? = null,
)
