package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.dto.AppLimitDto
import com.familyguard.core.data.dto.ScheduleDto
import com.familyguard.core.data.dto.WebFilterDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toDto
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.WebFilter
import com.familyguard.core.domain.repo.RulesRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads always come from Room (offline-first); writes go to Postgrest first
 * so a failed save is reported honestly instead of diverging from the server.
 * Refresh reconciles deletions too, so a rule removed by another parent
 * disappears after the next sync (PLAN M4 delta sync).
 */
@Singleton
class RulesRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : RulesRepository {

    override fun observeLimits(childId: String): Flow<List<AppLimit>> =
        db.appLimitDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override fun observeSchedules(childId: String): Flow<List<Schedule>> =
        db.scheduleDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override fun observeWebFilters(childId: String): Flow<List<WebFilter>> =
        db.webFilterDao().observe(childId).map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(childId: String): AppResult<Unit> = runApp {
        val limits = client.postgrest.from("app_limits")
            .select { filter { eq("child_id", childId) } }
            .decodeList<AppLimitDto>()
        val limitDao = db.appLimitDao()
        val keepL = limits.map { it.id }.toSet()
        limitDao.getForChild(childId).filter { it.id !in keepL }.forEach { limitDao.delete(it.id) }
        limits.forEach { limitDao.upsert(it.toDomain().toEntity()) }

        val schedules = client.postgrest.from("schedules")
            .select { filter { eq("child_id", childId) } }
            .decodeList<ScheduleDto>()
        val scheduleDao = db.scheduleDao()
        val keepS = schedules.map { it.id }.toSet()
        scheduleDao.getForChild(childId).filter { it.id !in keepS }.forEach { scheduleDao.delete(it.id) }
        schedules.forEach { scheduleDao.upsert(it.toDomain().toEntity()) }

        val filters = client.postgrest.from("web_filters")
            .select { filter { eq("child_id", childId) } }
            .decodeList<WebFilterDto>()
        val filterDao = db.webFilterDao()
        val keepW = filters.map { it.id }.toSet()
        filterDao.getForChild(childId).filter { it.id !in keepW }.forEach { filterDao.delete(it.id) }
        filters.forEach { filterDao.upsert(it.toDomain().toEntity()) }

        Unit
    }

    override suspend fun upsertLimit(limit: AppLimit): AppResult<AppLimit> = runApp {
        val withId = if (limit.id.isBlank()) limit.copy(id = UUID.randomUUID().toString()) else limit
        val saved = client.postgrest.from("app_limits")
            .upsert(withId.toDto()) { select() }
            .decodeSingleOrNull<AppLimitDto>() ?: withId.toDto()
        db.appLimitDao().upsert(saved.toDomain().toEntity())
        saved.toDomain()
    }

    override suspend fun deleteLimit(limit: AppLimit): AppResult<Unit> = runApp {
        client.postgrest.from("app_limits").delete { filter { eq("id", limit.id) } }
        db.appLimitDao().delete(limit.id)
        Unit
    }

    override suspend fun upsertSchedule(schedule: Schedule): AppResult<Schedule> = runApp {
        val withId = if (schedule.id.isBlank()) schedule.copy(id = UUID.randomUUID().toString()) else schedule
        val saved = client.postgrest.from("schedules")
            .upsert(withId.toDto()) { select() }
            .decodeSingleOrNull<ScheduleDto>() ?: withId.toDto()
        db.scheduleDao().upsert(saved.toDomain().toEntity())
        saved.toDomain()
    }

    override suspend fun deleteSchedule(schedule: Schedule): AppResult<Unit> = runApp {
        client.postgrest.from("schedules").delete { filter { eq("id", schedule.id) } }
        db.scheduleDao().delete(schedule.id)
        Unit
    }

    override suspend fun upsertWebFilter(filter: WebFilter): AppResult<WebFilter> = runApp {
        val withId = if (filter.id.isBlank()) filter.copy(id = UUID.randomUUID().toString()) else filter
        val saved = client.postgrest.from("web_filters")
            .upsert(withId.toDto()) { select() }
            .decodeSingleOrNull<WebFilterDto>() ?: withId.toDto()
        db.webFilterDao().upsert(saved.toDomain().toEntity())
        saved.toDomain()
    }

    override suspend fun deleteWebFilter(filter: WebFilter): AppResult<Unit> = runApp {
        client.postgrest.from("web_filters").delete { filter { eq("id", filter.id) } }
        db.webFilterDao().delete(filter.id)
        Unit
    }
}
