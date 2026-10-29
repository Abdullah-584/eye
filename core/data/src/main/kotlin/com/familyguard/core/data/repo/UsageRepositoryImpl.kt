package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.database.entity.UsageDailyEntity
import com.familyguard.core.database.entity.UsageEventEntity
import com.familyguard.core.data.dto.UsageDailyDto
import com.familyguard.core.data.dto.UsageEventDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.domain.model.UsageDaily
import com.familyguard.core.domain.model.UsageEvent
import com.familyguard.core.domain.repo.UsageRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : UsageRepository {

    private val dayFormat = DateTimeFormatter.ISO_LOCAL_DATE

    override fun observeUsage(childId: String): Flow<List<UsageDaily>> =
        db.usageDao().observeAll(childId).map { rows ->
            rows.map { UsageDaily(it.childId, it.packageName, it.day, it.seconds, it.pickups) }
        }

    override fun observeUsageRange(childId: String, fromDay: String, toDay: String): Flow<List<UsageDaily>> =
        db.usageDao().observeRange(childId, fromDay, toDay).map { rows ->
            rows.map { UsageDaily(it.childId, it.packageName, it.day, it.seconds, it.pickups) }
        }

    override suspend fun recordUsage(event: UsageEvent) {
        val zone = ZoneId.systemDefault()
        val day = dayFormat.format(Instant.parse(event.startedAt).atZone(zone).toLocalDate())
        db.usageDao().ensureRow(event.childId, event.packageName, day)
        db.usageDao().enqueueEvent(
            UsageEventEntity(
                childId = event.childId,
                deviceId = event.deviceId,
                packageName = event.packageName,
                eventType = event.eventType.name,
                startedAt = event.startedAt,
                durationSec = event.durationSec,
                day = day,
            ),
        )
        // Local aggregate for immediate enforcement + child-side display.
        db.usageDao().addSeconds(event.childId, event.packageName, day, event.durationSec.toLong())
    }

    /**
     * Uploads queued rows in batches of ≤500 with the server's unique key as
     * `onConflict` + `ignoreDuplicates`, so a retried batch that actually
     * landed is a no-op (PLAN §4.6 idempotency).
     */
    override suspend fun flushUploads(): AppResult<Int> = runApp {
        var uploaded = 0
        while (true) {
            val batch = db.usageDao().pendingEvents(500)
            if (batch.isEmpty()) break
            val dtos = batch.map {
                UsageEventDto(
                    it.childId,
                    it.deviceId,
                    it.packageName,
                    // The enum name is upper-camel (APP_SWITCH) but the column
                    // CHECKs against snake_case values ('app_switch'): sending
                    // the enum name made every batch fail with 23514, which
                    // silently emptied every parent-facing report.
                    it.eventType.lowercase(),
                    it.startedAt,
                    it.durationSec,
                )
            }
            client.postgrest.from("usage_events").upsert(dtos) {
                onConflict = "device_id,package_name,started_at,event_type"
                ignoreDuplicates = true
            }
            db.usageDao().markEventsSynced(batch.map { it.localId }, Instant.now().toString())
            uploaded += batch.size
        }
        uploaded
    }

    override suspend fun refreshReports(childId: String, timezone: String): AppResult<Int> = runApp {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())

        // 1) Preferred path: the rollup cron's output (usage_daily, RLS: parent read).
        val rollup = client.postgrest.from("usage_daily")
            .select { filter { eq("child_id", childId) } }
            .decodeList<UsageDailyDto>()

        if (rollup.isNotEmpty()) {
            db.usageDao().upsertDaily(
                rollup.map { UsageDailyEntity(it.childId, it.packageName, it.day, it.seconds, it.pickups) },
            )
            return@runApp rollup.size
        }

        // 2) Fallback while `report-rollup` is not deployed: aggregate the
        //    parent-readable raw events client-side (charts must not be dead).
        val events = client.postgrest.from("usage_events")
            .select {
                filter { eq("child_id", childId) }
                limit(5000L)
            }
            .decodeList<UsageEventDto>()

        val aggregated = events
            .groupBy { e ->
                val day = runCatching {
                    dayFormat.format(Instant.parse(e.startedAt).atZone(zone).toLocalDate())
                }.getOrDefault("")
                e.packageName to day
            }
            .filter { it.key.second.isNotEmpty() }
            .map { (key, rows) ->
                UsageDailyEntity(
                    childId = childId,
                    packageName = key.first,
                    day = key.second,
                    seconds = rows.sumOf { it.durationSec.toLong() },
                    pickups = rows.count {
                        it.eventType.equals("app_switch", ignoreCase = true)
                    },
                )
            }

        db.usageDao().upsertDaily(aggregated)
        aggregated.size
    }
}
