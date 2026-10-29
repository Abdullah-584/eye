package com.familyguard.core.data.internal

import com.familyguard.core.database.entity.AppLimitEntity
import com.familyguard.core.database.entity.ChildEntity
import com.familyguard.core.database.entity.DeviceEntity
import com.familyguard.core.database.entity.FamilyEntity
import com.familyguard.core.database.entity.LocationEntity
import com.familyguard.core.database.entity.ScheduleEntity
import com.familyguard.core.database.entity.TimeRequestEntity
import com.familyguard.core.database.entity.WebFilterEntity
import com.familyguard.core.data.dto.AuditDto
import com.familyguard.core.data.dto.ChildDto
import com.familyguard.core.data.dto.DeviceDto
import com.familyguard.core.data.dto.FamilyDto
import com.familyguard.core.data.dto.LocationDto
import com.familyguard.core.data.dto.AppLimitDto
import com.familyguard.core.data.dto.ScheduleDto
import com.familyguard.core.data.dto.TimeRequestDto
import com.familyguard.core.data.dto.WebFilterDto
import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.AuditEntry
import com.familyguard.core.domain.model.Child
import com.familyguard.core.domain.model.Device
import com.familyguard.core.domain.model.DeviceStatus
import com.familyguard.core.domain.model.Family
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.ScheduleAction
import com.familyguard.core.domain.model.TimeRequest
import com.familyguard.core.domain.model.TimeRequestStatus
import com.familyguard.core.domain.model.WebFilter
import com.familyguard.core.domain.model.WebFilterMode
import com.familyguard.core.domain.model.WebResolver
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** Postgres `time` arrives as "HH:MM:SS"; the app uses "HH:MM". */
internal fun String.toHhMm(): String = if (length >= 5) substring(0, 5) else this

// ---------------- DTO -> domain ----------------

fun FamilyDto.toDomain() = Family(id, name, createdBy, createdAt, updatedAt)

fun ChildDto.toDomain() = Child(id, familyId, displayName, birthDate, timezone, createdAt, updatedAt)

fun DeviceDto.toDomain() = Device(
    id = id,
    childId = childId,
    deviceName = deviceName,
    model = model,
    androidSdk = androidSdk,
    appVersion = appVersion,
    fcmToken = fcmToken,
    status = runCatching { DeviceStatus.valueOf(status.uppercase()) }.getOrDefault(DeviceStatus.PENDING),
    pairedAt = pairedAt,
    lastSeenAt = lastSeenAt,
)

fun AppLimitDto.toDomain() = AppLimit(id, childId, packageName, dailyLimitSeconds, enabled, version, updatedAt)

fun ScheduleDto.toDomain() = Schedule(
    id = id,
    childId = childId,
    name = name,
    daysMask = daysMask,
    startTime = startTime.toHhMm(),
    endTime = endTime.toHhMm(),
    action = if (action.equals("warn", true)) ScheduleAction.WARN else ScheduleAction.LOCK,
    enabled = enabled,
    version = version,
    updatedAt = updatedAt,
)

fun WebFilterDto.toDomain() = WebFilter(
    id = id,
    childId = childId,
    mode = if (mode.equals("allowlist", true)) WebFilterMode.ALLOWLIST else WebFilterMode.BLOCKLIST,
    domains = domains,
    categories = categories,
    resolver = if (resolver == "local_blocklist") WebResolver.LOCAL_BLOCKLIST else WebResolver.SAFE_DNS,
    enabled = enabled,
    version = version,
    updatedAt = updatedAt,
)

fun TimeRequestDto.toDomain() = TimeRequest(
    id = id,
    childId = childId,
    deviceId = deviceId,
    packageName = packageName,
    requestedSeconds = requestedSeconds,
    reason = reason,
    status = runCatching { TimeRequestStatus.valueOf(status.uppercase()) }.getOrDefault(TimeRequestStatus.PENDING),
    grantedSeconds = grantedSeconds,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    createdAt = createdAt,
)

fun AuditDto.toDomain() = AuditEntry(id, familyId, actorId, actorType, action, target, createdAt)

// ---------------- domain -> DTO (inserts) ----------------

fun Family.newDto(createdBy: String) = FamilyDto(id = id, name = name, createdBy = createdBy)

fun Child.newDto() = ChildDto(
    id = id,
    familyId = familyId,
    displayName = displayName,
    birthDate = birthDate,
    timezone = timezone,
)

fun AppLimit.toDto() = AppLimitDto(id, childId, packageName, dailyLimitSeconds, enabled, version, updatedAt)

fun Schedule.toDto() = ScheduleDto(
    id = id,
    childId = childId,
    name = name,
    daysMask = daysMask,
    startTime = startTime,
    endTime = endTime,
    action = if (action == ScheduleAction.WARN) "warn" else "lock",
    enabled = enabled,
    version = version,
    updatedAt = updatedAt,
)

fun WebFilter.toDto() = WebFilterDto(
    id = id,
    childId = childId,
    mode = if (mode == WebFilterMode.ALLOWLIST) "allowlist" else "blocklist",
    domains = domains,
    categories = categories,
    resolver = if (resolver == WebResolver.LOCAL_BLOCKLIST) "local_blocklist" else "safe_dns",
    enabled = enabled,
    version = version,
    updatedAt = updatedAt,
)

// ---------------- DTO -> entity (Room cache of a fetched row) ----------------

fun FamilyDto.toEntity() = FamilyEntity(id, name, createdBy, createdAt, updatedAt)

fun ChildDto.toEntity() = ChildEntity(id, familyId, displayName, birthDate, timezone, createdAt, updatedAt)

fun DeviceDto.toEntity() = DeviceEntity(
    id, childId, deviceName, model, androidSdk, appVersion, fcmToken, status, pairedAt, lastSeenAt,
)

fun LocationDto.toDomain() = com.familyguard.core.domain.model.LocationFix(
    childId, deviceId, lat, lng, accuracyM, batteryPct, recordedAt,
)

// ---------------- domain -> entity (Room) ----------------

fun Family.toEntity() = FamilyEntity(id, name, createdBy, createdAt, updatedAt)
fun FamilyEntity.toDomain() = Family(id, name, createdBy, createdAt, updatedAt)

fun Child.toEntity() = ChildEntity(id, familyId, displayName, birthDate, timezone, createdAt, updatedAt)
fun ChildEntity.toDomain() = Child(id, familyId, displayName, birthDate, timezone, createdAt, updatedAt)

fun Device.toEntity() = DeviceEntity(
    id, childId, deviceName, model, androidSdk, appVersion, fcmToken,
    status.name.lowercase(), pairedAt, lastSeenAt,
)
fun DeviceEntity.toDomain() = Device(
    id, childId, deviceName, model, androidSdk, appVersion, fcmToken,
    runCatching { DeviceStatus.valueOf(status.uppercase()) }.getOrDefault(DeviceStatus.PENDING),
    pairedAt, lastSeenAt,
)

fun AppLimit.toEntity() = AppLimitEntity(id, childId, packageName, dailyLimitSeconds, enabled, version, updatedAt)
fun AppLimitEntity.toDomain() = AppLimit(id, childId, packageName, dailyLimitSeconds, enabled, version, updatedAt)

fun Schedule.toEntity() = ScheduleEntity(
    id, childId, name, daysMask, startTime, endTime,
    if (action == ScheduleAction.WARN) "warn" else "lock", enabled, version, updatedAt,
)
fun ScheduleEntity.toDomain() = Schedule(
    id, childId, name, daysMask, startTime, endTime,
    if (action == "warn") ScheduleAction.WARN else ScheduleAction.LOCK, enabled, version, updatedAt,
)

fun WebFilter.toEntity() = WebFilterEntity(
    id, childId,
    if (mode == WebFilterMode.ALLOWLIST) "allowlist" else "blocklist",
    json.encodeToString(domains),
    json.encodeToString(categories),
    if (resolver == WebResolver.LOCAL_BLOCKLIST) "local_blocklist" else "safe_dns",
    enabled, version, updatedAt,
)
fun WebFilterEntity.toDomain() = WebFilter(
    id, childId,
    if (mode == "allowlist") WebFilterMode.ALLOWLIST else WebFilterMode.BLOCKLIST,
    runCatching { json.decodeFromString<List<String>>(domainsJson) }.getOrDefault(emptyList()),
    runCatching { json.decodeFromString<List<String>>(categoriesJson) }.getOrDefault(emptyList()),
    if (resolver == "local_blocklist") WebResolver.LOCAL_BLOCKLIST else WebResolver.SAFE_DNS,
    enabled, version, updatedAt,
)

fun TimeRequest.toEntity() = TimeRequestEntity(
    id, childId, deviceId, packageName, requestedSeconds, reason,
    status.name.lowercase(), grantedSeconds, decidedBy, decidedAt, createdAt,
)
fun TimeRequestEntity.toDomain() = TimeRequest(
    id, childId, deviceId, packageName, requestedSeconds, reason,
    runCatching { TimeRequestStatus.valueOf(status.uppercase()) }.getOrDefault(TimeRequestStatus.PENDING),
    grantedSeconds, decidedBy, decidedAt, createdAt,
)

fun com.familyguard.core.domain.model.LocationFix.toEntity(syncedAt: String? = null) = LocationEntity(
    childId = childId, deviceId = deviceId, lat = lat, lng = lng,
    accuracyM = accuracyM, batteryPct = batteryPct, recordedAt = recordedAt, syncedAt = syncedAt,
)
fun LocationEntity.toDomain() = com.familyguard.core.domain.model.LocationFix(
    childId, deviceId, lat, lng, accuracyM, batteryPct, recordedAt,
)
