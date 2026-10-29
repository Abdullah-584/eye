package com.familyguard.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room mirror of the Supabase schema (PLAN §M4 — "Room schema mirroring rules
 * tables"). Column names deliberately match the server so mapping is a field
 * rename. Every table is app-private; noBackupFilesDir is excluded at the
 * manifest level (PLAN §7.6).
 */

@Entity(tableName = "families")
data class FamilyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Entity(
    tableName = "children",
    indices = [Index("familyId")],
)
data class ChildEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val displayName: String,
    val birthDate: String? = null,
    val timezone: String = "UTC",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Entity(
    tableName = "devices",
    indices = [Index("childId")],
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val deviceName: String,
    val model: String? = null,
    val androidSdk: Int? = null,
    val appVersion: String? = null,
    val fcmToken: String? = null,
    val status: String = "pending",
    val pairedAt: String? = null,
    val lastSeenAt: String? = null,
)

@Entity(
    tableName = "app_limits",
    indices = [Index("childId")],
)
data class AppLimitEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val packageName: String,
    val dailyLimitSeconds: Int,
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: String = "",
)

@Entity(
    tableName = "schedules",
    indices = [Index("childId")],
)
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val name: String,
    val daysMask: Int = 127,
    val startTime: String,
    val endTime: String,
    val action: String = "lock",
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: String = "",
)

@Entity(
    tableName = "web_filters",
    indices = [Index("childId")],
)
data class WebFilterEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val mode: String = "blocklist",
    /** JSON array; kept as text to avoid a Room TypeConverter for String[]. */
    val domainsJson: String = "[]",
    val categoriesJson: String = "[]",
    val resolver: String = "safe_dns",
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: String = "",
)

@Entity(
    tableName = "time_requests",
    indices = [Index("childId"), Index("status")],
)
data class TimeRequestEntity(
    @PrimaryKey val id: String,
    val childId: String,
    val deviceId: String,
    val packageName: String? = null,
    val requestedSeconds: Int,
    val reason: String? = null,
    val status: String = "pending",
    val grantedSeconds: Int? = null,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val createdAt: String = "",
)

@Entity(
    tableName = "usage_daily",
    primaryKeys = ["childId", "packageName", "day"],
)
data class UsageDailyEntity(
    val childId: String,
    val packageName: String,
    /** Local day of the child, yyyy-MM-dd. */
    val day: String,
    val seconds: Long = 0,
    val pickups: Int = 0,
)

/**
 * Raw usage facts pending upload. [syncedAt] null = still queued; the unique
 * key below mirrors the server's idempotency constraint so a retried batch
 * cannot double-count (PLAN §4.6).
 */
@Entity(
    tableName = "usage_events_pending",
    indices = [Index(value = ["childId", "day"]), Index("childId")],
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val childId: String,
    val deviceId: String,
    val packageName: String,
    val eventType: String,
    val startedAt: String,
    val durationSec: Int,
    val day: String,
    val syncedAt: String? = null,
)

@Entity(
    tableName = "locations_pending",
    indices = [Index("childId")],
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val childId: String,
    val deviceId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val batteryPct: Int? = null,
    val recordedAt: String,
    val syncedAt: String? = null,
)

/** Delta-sync bookkeeping: `updated_at > lastSyncAt` pulls on reconnect. */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    // Not `table`: that is a reserved SQL keyword and breaks raw queries.
    @PrimaryKey val tableName: String,
    val lastSyncAt: String = "",
)
