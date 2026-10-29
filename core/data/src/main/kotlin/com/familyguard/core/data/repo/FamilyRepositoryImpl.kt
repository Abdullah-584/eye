package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.dto.AuditDto
import com.familyguard.core.data.dto.ChildDto
import com.familyguard.core.data.dto.DeviceDto
import com.familyguard.core.data.dto.FamilyDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.domain.model.AuditEntry
import com.familyguard.core.domain.model.Child
import com.familyguard.core.domain.model.Device
import com.familyguard.core.domain.model.Family
import com.familyguard.core.domain.repo.FamilyRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FamilyRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : FamilyRepository {

    /** Audit rows are display-only (consent record, history) — small and cached in memory. */
    private val auditCache = MutableStateFlow<List<AuditEntry>>(emptyList())

    private suspend fun uid(): String? = client.auth.currentUserOrNull()?.id

    private suspend fun myFamilyId(): String? {
        val me = uid() ?: return null
        return client.postgrest
            .from("family_members")
            .select { filter { eq("profile_id", me) } }
            .decodeList<FamilyMemberDto>()
            .firstOrNull()?.familyId
    }

    override suspend fun getFamily(): AppResult<Family?> = runApp {
        val me = uid() ?: return@runApp null
        val memberOf = client.postgrest
            .from("family_members")
            .select { filter { eq("profile_id", me) } }
            .decodeList<FamilyMemberDto>()
            .firstOrNull()?.familyId
        if (memberOf == null) {
            db.familyDao().clear()
            null
        } else {
            val dto = client.postgrest
                .from("families")
                .select { filter { eq("id", memberOf) } }
                .decodeList<FamilyDto>()
                .firstOrNull()
            dto?.let {
                db.familyDao().upsertFamily(it.toEntity())
                it.toDomain()
            }
        }
    }

    /**
     * Creates the family, the caller's parent membership row, and the
     * verifiable-consent audit row — all in one server round-trip sequence
     * (PLAN §6.3: no collection before consent is recorded).
     */
    override suspend fun createFamily(name: String, consentPolicyVersion: String): AppResult<Family> = runApp {
        val me = uid() ?: error("not signed in")
        val familyId = UUID.randomUUID().toString()

        client.postgrest.from("families").insert(
            FamilyDto(id = familyId, name = name, createdBy = me),
        )

        client.postgrest.from("family_members").insert(
            FamilyMemberDto(familyId = familyId, profileId = me, role = "parent"),
        )

        client.postgrest.from("audit_log").insert(
            AuditDto(
                familyId = familyId,
                actorId = me,
                actorType = "parent",
                action = "consent.granted",
                metadata = buildJsonObject {
                    put("policy_version", consentPolicyVersion)
                    put("scope", "location,app_usage,web_filter")
                },
            ),
        )

        val family = Family(familyId, name, me)
        db.familyDao().upsertFamily(family.toEntity())
        refreshAudit()
        family
    }

    override suspend fun addChild(name: String, birthYear: Int?, timezone: String): AppResult<Child> = runApp {
        val me = uid() ?: error("not signed in")
        val familyId = myFamilyId() ?: error("create a family first")
        val childId = UUID.randomUUID().toString()
        val birthDate = birthYear?.let { "$it-01-01" }

        client.postgrest.from("children").insert(
            ChildDto(
                id = childId,
                familyId = familyId,
                displayName = name,
                birthDate = birthDate,
                timezone = timezone,
            ),
        )

        val child = Child(childId, familyId, name, birthDate, timezone)
        db.childDao().upsertAll(listOf(child.toEntity()))
        child
    }

    override fun observeChildren(): Flow<List<Child>> =
        db.childDao().observeChildren().map { list -> list.map { it.toDomain() } }

    override suspend fun refreshChildren(): AppResult<List<Child>> = runApp {
        val familyId = myFamilyId() ?: return@runApp emptyList()
        val rows = client.postgrest
            .from("children")
            .select { filter { eq("family_id", familyId) } }
            .decodeList<ChildDto>()
        val dao = db.childDao()
        val existing = dao.getChildren().map { it.id }.toSet()
        val received = rows.map { it.id }.toSet()
        (existing - received).forEach { dao.deleteById(it) }
        dao.upsertAll(rows.map { it.toEntity() })
        rows.map { it.toDomain() }
    }

    override fun observeDevices(): Flow<List<Device>> =
        db.deviceDao().observeDevices().map { list -> list.map { it.toDomain() } }

    override suspend fun refreshDevices(): AppResult<List<Device>> = runApp {
        val childIds = db.childDao().getChildren().map { it.id }
        if (childIds.isEmpty()) return@runApp emptyList()
        val rows = client.postgrest
            .from("devices")
            .select { filter { isIn("child_id", childIds) } }
            .decodeList<DeviceDto>()
        db.deviceDao().upsertAll(rows.map { it.toEntity() })
        rows.map { it.toDomain() }
    }

    /**
     * Revocation prefers the `pairing-revoke` Edge Function (it kills the
     * device's refresh tokens server-side, PLAN §M3). If the function is not
     * deployed yet we still flip the row via RLS — the device loses read
     * access immediately — and the caller surfaces the degraded state.
     */
    override suspend fun revokeDevice(deviceId: String): AppResult<Unit> = runApp {
        runCatching {
            client.functions.invoke(
                "pairing-revoke",
                body = kotlinx.serialization.json.buildJsonObject { put("device_id", deviceId) },
            )
        }.getOrElse {
            client.postgrest.from("devices").update(
                { set("status", "revoked") },
                { filter { eq("id", deviceId) } },
            )
        }
        refreshDevices()
        Unit
    }

    override fun observeAudit(): Flow<List<AuditEntry>> = auditCache

    override suspend fun refreshAudit(): AppResult<List<AuditEntry>> = runApp {
        val familyId = myFamilyId() ?: return@runApp emptyList()
        val rows = client.postgrest
            .from("audit_log")
            .select {
                filter { eq("family_id", familyId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList<AuditDto>()
        val entries = rows.map { it.toDomain() }.sortedByDescending { it.createdAt }
        auditCache.value = entries
        entries
    }
}

/**
 * `family_members.role` is NOT NULL **with no default in Postgres**, and
 * supabase-kt encodes with `encodeDefaults = false` — so a defaulted `role`
 * would be dropped from the payload and the insert would fail with 23502
 * ("null value in column role"). It must therefore be a required field.
 */
@kotlinx.serialization.Serializable
private data class FamilyMemberDto(
    @kotlinx.serialization.SerialName("family_id") val familyId: String,
    @kotlinx.serialization.SerialName("profile_id") val profileId: String,
    val role: String,
)
