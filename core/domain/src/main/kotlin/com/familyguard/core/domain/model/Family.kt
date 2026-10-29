package com.familyguard.core.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain models for the family graph. These are plain Kotlin so the
 * enforcement logic can be unit-tested on the JVM without Android.
 */

@Serializable
data class Family(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class Child(
    val id: String,
    val familyId: String,
    val displayName: String,
    val birthDate: String? = null,
    val timezone: String = "UTC",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class Device(
    val id: String,
    val childId: String,
    val deviceName: String,
    val model: String? = null,
    val androidSdk: Int? = null,
    val appVersion: String? = null,
    val fcmToken: String? = null,
    val status: DeviceStatus = DeviceStatus.PENDING,
    val pairedAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
enum class DeviceStatus { PENDING, ACTIVE, REVOKED; }

/** Row written to `audit_log` for the verifiable-consent requirement. */
@Serializable
data class AuditEntry(
    val id: Long? = null,
    val familyId: String,
    val actorId: String? = null,
    val actorType: String, // parent | device | system
    val action: String,    // e.g. consent.granted, rule.updated
    val target: String? = null,
    val createdAt: String = "",
)

@Serializable
data class TimeRequest(
    val id: String,
    val childId: String,
    val deviceId: String,
    val packageName: String? = null,
    val requestedSeconds: Int,
    val reason: String? = null,
    val status: TimeRequestStatus = TimeRequestStatus.PENDING,
    val grantedSeconds: Int? = null,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val createdAt: String = "",
)

@Serializable
enum class TimeRequestStatus { PENDING, APPROVED, DENIED, EXPIRED; }
