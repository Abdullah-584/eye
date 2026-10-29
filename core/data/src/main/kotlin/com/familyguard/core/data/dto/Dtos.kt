package com.familyguard.core.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire DTOs for the public schema. Field names use @SerialName so Kotlin
 * properties can stay camelCase while matching snake_case columns exactly.
 */

@Serializable
data class FamilyDto(
    val id: String,
    val name: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ChildDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("birth_date") val birthDate: String? = null,
    val timezone: String = "UTC",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    @SerialName("device_name") val deviceName: String,
    val model: String? = null,
    @SerialName("android_sdk") val androidSdk: Int? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
    val status: String = "pending",
    @SerialName("paired_at") val pairedAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class AppLimitDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("daily_limit_seconds") val dailyLimitSeconds: Int,
    val enabled: Boolean = true,
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ScheduleDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    val name: String,
    @SerialName("days_mask") val daysMask: Int = 127,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val action: String = "lock",
    val enabled: Boolean = true,
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class WebFilterDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    val mode: String = "blocklist",
    val domains: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val resolver: String = "safe_dns",
    val enabled: Boolean = true,
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class TimeRequestDto(
    val id: String,
    @SerialName("child_id") val childId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("requested_seconds") val requestedSeconds: Int,
    val reason: String? = null,
    val status: String = "pending",
    @SerialName("granted_seconds") val grantedSeconds: Int? = null,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class UsageEventDto(
    @SerialName("child_id") val childId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_sec") val durationSec: Int,
)

@Serializable
data class UsageDailyDto(
    @SerialName("child_id") val childId: String,
    @SerialName("package_name") val packageName: String,
    val day: String,
    val seconds: Long = 0,
    val pickups: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class LocationDto(
    val id: Long? = null,
    @SerialName("child_id") val childId: String,
    @SerialName("device_id") val deviceId: String,
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class AuditDto(
    val id: Long? = null,
    @SerialName("family_id") val familyId: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("actor_type") val actorType: String,
    val action: String,
    val target: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
)
