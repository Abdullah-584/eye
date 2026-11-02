package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.dto.AdminEventDto
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.domain.model.AdminEvent
import com.familyguard.core.domain.model.AdminEventType
import com.familyguard.core.domain.repo.AdminEventRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M2a — `admin_events`: the child *reports* what it observed (RLS restricts
 * inserts to its own child+device pair); the parent reads + acknowledges.
 * The mapping between these sides is exactly the policy set in the
 * 20261031000000 migration — never widened here.
 */
@Singleton
class AdminEventRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : AdminEventRepository {

    override suspend fun report(childId: String, deviceId: String, enabled: Boolean): AppResult<Unit> =
        runApp {
            val row = AdminEventDto(
                id = UUID.randomUUID().toString(),
                childId = childId,
                deviceId = deviceId,
                eventType = if (enabled) AdminEventType.REENABLED else AdminEventType.DISABLED,
                occurredAt = Instant.now().toString(),
            )
            client.postgrest.from("admin_events").insert(row)
            db.adminEventDao().upsertAll(listOf(row.toEntity()))
        }

    override fun observe(childId: String): Flow<List<AdminEvent>> =
        db.adminEventDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(childId: String): AppResult<Int> = runApp {
        val rows = client.postgrest.from("admin_events")
            .select { filter { eq("child_id", childId) } }
            .decodeList<AdminEventDto>()
        db.adminEventDao().upsertAll(rows.map { it.toEntity() })
        rows.size
    }

    /** Parent-only (RLS): stamps `parent_ack_at`; the row stays for history. */
    override suspend fun acknowledge(eventId: String): AppResult<Unit> = runApp {
        client.postgrest.from("admin_events").update(
            { set("parent_ack_at", Instant.now().toString()) },
            { filter { eq("id", eventId) } },
        )
    }
}
