package com.familyguard.core.domain.repo

import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.AuditEntry
import com.familyguard.core.domain.model.Child
import com.familyguard.core.domain.model.Device
import com.familyguard.core.domain.model.Family
import com.familyguard.core.domain.model.LocationFix
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.TimeRequest
import com.familyguard.core.domain.model.UsageDaily
import com.familyguard.core.domain.model.WebFilter
import com.familyguard.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository contracts (Clean Architecture: interfaces in :core:domain,
 * implementations in :core:data backed by Room + Postgrest).
 *
 * All writes go through Postgrest with the caller's JWT — RLS is the
 * authority. Reads observe Room so the UI is offline-first and updates the
 * moment the sync engine writes a row.
 */

interface FamilyRepository {
    /** The signed-in parent's family, or null when they have not created one. */
    suspend fun getFamily(): AppResult<Family?>

    suspend fun createFamily(name: String, consentPolicyVersion: String): AppResult<Family>

    suspend fun addChild(name: String, birthYear: Int?, timezone: String): AppResult<Child>

    fun observeChildren(): Flow<List<Child>>

    suspend fun refreshChildren(): AppResult<List<Child>>

    fun observeDevices(): Flow<List<Device>>

    suspend fun refreshDevices(): AppResult<List<Device>>

    suspend fun revokeDevice(deviceId: String): AppResult<Unit>

    fun observeAudit(): Flow<List<AuditEntry>>
    suspend fun refreshAudit(): AppResult<List<AuditEntry>>
}

interface RulesRepository {
    fun observeLimits(childId: String): Flow<List<AppLimit>>
    fun observeSchedules(childId: String): Flow<List<Schedule>>
    fun observeWebFilters(childId: String): Flow<List<WebFilter>>

    suspend fun refresh(childId: String): AppResult<Unit>

    suspend fun upsertLimit(limit: AppLimit): AppResult<AppLimit>
    suspend fun deleteLimit(limit: AppLimit): AppResult<Unit>

    suspend fun upsertSchedule(schedule: Schedule): AppResult<Schedule>
    suspend fun deleteSchedule(schedule: Schedule): AppResult<Unit>

    suspend fun upsertWebFilter(filter: WebFilter): AppResult<WebFilter>
    suspend fun deleteWebFilter(filter: WebFilter): AppResult<Unit>
}

interface TimeRequestRepository {
    /** Parent inbox. */
    fun observeRequests(childId: String): Flow<List<TimeRequest>>

    /** Child-side: creates a pending row (RLS restricts to own device). */
    suspend fun requestMoreTime(
        childId: String,
        deviceId: String,
        requestedSeconds: Int,
        packageName: String?,
        reason: String?,
    ): AppResult<TimeRequest>

    /** Parent decision — always through the `time-request-decide` function. */
    suspend fun decide(requestId: String, approve: Boolean, grantedSeconds: Int?): AppResult<TimeRequest>

    suspend fun refresh(childId: String): AppResult<List<TimeRequest>>
}

interface UsageRepository {
    /** Local daily totals for today (enforcement + dashboards). */
    fun observeUsage(childId: String): Flow<List<UsageDaily>>

    /** Local aggregates for report charts (7/30 days). */
    fun observeUsageRange(childId: String, fromDay: String, toDay: String): Flow<List<UsageDaily>>

    /** Queue a usage fact for upload (never blocks on network). */
    suspend fun recordUsage(event: com.familyguard.core.domain.model.UsageEvent)

    /** Force a flush of the pending upload queue (screen-off / reconnect). */
    suspend fun flushUploads(): AppResult<Int>

    /**
     * Parent-side report refresh: pulls `usage_daily` (the rollup cron's
     * output). When the rollup function is not deployed yet it falls back to
     * aggregating the parent-readable `usage_events` client-side, so charts
     * work either way; results land in the Room cache for offline viewing.
     */
    suspend fun refreshReports(childId: String, timezone: String): AppResult<Int>
}

interface LocationRepository {
    fun observeLatest(childId: String): Flow<LocationFix?>

    suspend fun recordFix(fix: LocationFix)

    suspend fun flushUploads(): AppResult<Int>

    suspend fun refreshLatest(childId: String): AppResult<LocationFix?>
}

/** Parent-side pairing + child-side join, both via Edge Functions. */
interface PairingRepository {
    /** P8: generate a 6-char code (5 min TTL) for [childId]. */
    suspend fun createCode(childId: String): AppResult<PairingCode>

    /** C1: exchange the code for a *device* session scoped to one child. */
    suspend fun joinFamily(code: String, deviceName: String, model: String, sdk: Int, appVersion: String): AppResult<JoinResult>

    /** Periodic device heartbeat (last_seen_at + fcm token + health). */
    suspend fun heartbeat(fcmToken: String?, appVersion: String, sdk: Int): AppResult<Unit>
}

data class PairingCode(val code: String, val expiresAt: String)

data class JoinResult(
    val childId: String,
    val deviceId: String,
)
