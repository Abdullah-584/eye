package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.dto.TimeRequestDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.domain.model.TimeRequest
import com.familyguard.core.domain.model.TimeRequestStatus
import com.familyguard.core.domain.repo.TimeRequestRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class DecideResponse(
    val status: String = "pending",
    @kotlinx.serialization.SerialName("granted_seconds") val grantedSeconds: Int? = null,
)

@Singleton
class TimeRequestRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : TimeRequestRepository {

    override fun observeRequests(childId: String): Flow<List<TimeRequest>> =
        db.timeRequestDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override suspend fun requestMoreTime(
        childId: String,
        deviceId: String,
        requestedSeconds: Int,
        packageName: String?,
        reason: String?,
    ): AppResult<TimeRequest> = runApp {
        val body = TimeRequestDto(
            id = UUID.randomUUID().toString(),
            childId = childId,
            deviceId = deviceId,
            packageName = packageName,
            requestedSeconds = requestedSeconds,
            reason = reason,
            status = "pending",
        )
        // RLS on time_requests lets a device create only its own pending rows
        // and forbids decision fields entirely (PLAN §4.3.10).
        val saved = client.postgrest.from("time_requests")
            .insert(body) { select() }
            .decodeSingleOrNull<TimeRequestDto>() ?: body
        db.timeRequestDao().upsert(saved.toDomain().toEntity())
        saved.toDomain()
    }

    /**
     * The decision ALWAYS goes through `time-request-decide` (service_role):
     * `decided_by`, `granted_seconds` and `status` are forgeable only by the
     * server, never by either client (PLAN §4.3.10). If the function is not
     * deployed the call fails loudly — we never fake a local approval.
     */
    override suspend fun decide(requestId: String, approve: Boolean, grantedSeconds: Int?): AppResult<TimeRequest> =
        runApp {
            val response = client.functions.invoke(
                "time-request-decide",
                body = buildJsonObject {
                    put("request_id", requestId)
                    put("decision", if (approve) "approve" else "deny")
                    grantedSeconds?.let { put("granted_seconds", it) }
                },
            )
            val decided = json.decodeFromString<DecideResponse>(response.bodyAsText())
            val existing = db.timeRequestDao().getById(requestId)
            val merged = (existing?.toDomain() ?: TimeRequest(
                id = requestId,
                childId = "",
                deviceId = "",
                requestedSeconds = 0,
            )).copy(
                status = runCatching { TimeRequestStatus.valueOf(decided.status.uppercase()) }
                    .getOrDefault(TimeRequestStatus.PENDING),
                grantedSeconds = decided.grantedSeconds,
            )
            if (existing != null) db.timeRequestDao().upsert(merged.toEntity())
            merged
        }

    override suspend fun refresh(childId: String): AppResult<List<TimeRequest>> = runApp {
        val rows = client.postgrest.from("time_requests")
            .select { filter { eq("child_id", childId) } }
            .decodeList<TimeRequestDto>()
        db.timeRequestDao().upsertAll(rows.map { it.toDomain().toEntity() })
        rows.map { it.toDomain() }
    }
}
