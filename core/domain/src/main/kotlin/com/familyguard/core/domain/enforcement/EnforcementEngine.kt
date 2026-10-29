package com.familyguard.core.domain.enforcement

import java.time.Instant
import java.time.ZoneId

/**
 * The decision the enforcement service makes every poll tick (PLAN M4/M5):
 * "does the child's current foreground app have to be blocked right now?".
 * Pure and clock-injected so the arithmetic is unit-tested (PLAN §12.1).
 */
object EnforcementEngine {

    /**
     * @param limits today's per-app limits
     * @param usedSecondsToday per-package accumulated seconds (local day)
     * @param activeSchedules schedules that may lock the whole device
     * @param blockedApps extra domain-rule packages (e.g. from `rules` kind=app_block)
     */
    fun evaluate(
        foregroundPackage: String?,
        limits: List<AppLimitView>,
        usedSecondsToday: Map<String, Long>,
        activeSchedules: List<ScheduleView>,
        blockedApps: Set<String> = emptySet(),
        now: Instant,
        zone: ZoneId,
    ): EnforcementDecision {
        val scheduleLock = activeSchedules.firstOrNull { it.enabled && it.name.isNotEmpty() }
        if (scheduleLock != null) {
            return EnforcementDecision(
                blockedPackage = foregroundPackage,
                reason = BlockReason.SCHEDULE,
                scheduleName = scheduleLock.name,
            )
        }

        if (foregroundPackage != null && foregroundPackage in blockedApps) {
            return EnforcementDecision(
                blockedPackage = foregroundPackage,
                reason = BlockReason.APP_BLOCKED,
            )
        }

        val limit = limits.firstOrNull { it.packageName == foregroundPackage }
            ?: return EnforcementDecision(blockedPackage = null, reason = BlockReason.NONE)

        val used = usedSecondsToday[foregroundPackage] ?: 0L
        return if (LimitEvaluator.shouldBlock(limit, used, now, zone)) {
            EnforcementDecision(
                blockedPackage = foregroundPackage,
                reason = BlockReason.LIMIT_REACHED,
                limitSeconds = limit.dailyLimitSeconds,
                usedSeconds = used,
            )
        } else {
            EnforcementDecision(blockedPackage = null, reason = BlockReason.NONE)
        }
    }
}

data class EnforcementDecision(
    val blockedPackage: String?,
    val reason: BlockReason,
    val limitSeconds: Int = 0,
    val usedSeconds: Long = 0,
    val scheduleName: String? = null,
)

enum class BlockReason { NONE, LIMIT_REACHED, SCHEDULE, APP_BLOCKED }
