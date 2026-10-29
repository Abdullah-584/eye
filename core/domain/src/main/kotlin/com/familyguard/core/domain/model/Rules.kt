package com.familyguard.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Rule models. Every rule row carries a monotonic `version`; the sync engine
 * only applies an incoming row when `version` is >= the local one, which makes
 * out-of-order Realtime events harmless (last-writer-wins by version).
 */

@Serializable
data class AppLimit(
    val id: String,
    val childId: String,
    val packageName: String,
    val dailyLimitSeconds: Int,
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: String = "",
)

@Serializable
data class Schedule(
    val id: String,
    val childId: String,
    val name: String,
    /** Bit 0 = Sunday … bit 6 = Saturday, 0..127. */
    val daysMask: Int = 127,
    /** Local time "HH:mm" in the child's timezone. */
    val startTime: String,
    val endTime: String,
    val action: ScheduleAction = ScheduleAction.LOCK,
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: String = "",
)

@Serializable
enum class ScheduleAction { LOCK, WARN; }

@Serializable
data class WebFilter(
    val id: String,
    val childId: String,
    val mode: WebFilterMode = WebFilterMode.BLOCKLIST,
    val domains: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val resolver: WebResolver = WebResolver.SAFE_DNS,
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: String = "",
)

@Serializable
enum class WebFilterMode { BLOCKLIST, ALLOWLIST; }

@Serializable
enum class WebResolver { SAFE_DNS, LOCAL_BLOCKLIST; }

/** Raw usage fact queued for upload (idempotent via the server unique key). */
@Serializable
data class UsageEvent(
    val childId: String,
    val deviceId: String,
    val packageName: String,
    val eventType: UsageEventType,
    /** ISO-8601 UTC. */
    val startedAt: String,
    val durationSec: Int,
)

@Serializable
enum class UsageEventType { APP_SWITCH, SCREEN_ON, SCREEN_OFF; }

/** Aggregated per-day counters used by local enforcement + report charts. */
@Serializable
data class UsageDaily(
    val childId: String,
    val packageName: String,
    /** Local date of the child, "yyyy-MM-dd". */
    val day: String,
    val seconds: Long = 0,
    val pickups: Int = 0,
)

@Serializable
data class LocationFix(
    val childId: String,
    val deviceId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val batteryPct: Int? = null,
    /** ISO-8601 UTC. */
    val recordedAt: String,
)
