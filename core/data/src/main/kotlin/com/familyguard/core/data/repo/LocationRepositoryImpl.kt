package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.data.dto.LocationDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.domain.model.LocationFix
import com.familyguard.core.domain.repo.LocationRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
) : LocationRepository {

    override fun observeLatest(childId: String): Flow<LocationFix?> =
        db.locationDao().observeLatest(childId).map { it?.toDomain() }

    override suspend fun recordFix(fix: LocationFix) {
        // Queued locally first: location must never depend on connectivity
        // (PLAN §4.6 pattern — network only on the WorkManager path).
        db.locationDao().enqueue(fix.toEntity())
    }

    override suspend fun flushUploads(): AppResult<Int> = runApp {
        var uploaded = 0
        while (true) {
            val batch = db.locationDao().pending(100)
            if (batch.isEmpty()) break
            client.postgrest.from("locations").insert(
                batch.map {
                    LocationDto(
                        childId = it.childId,
                        deviceId = it.deviceId,
                        lat = it.lat,
                        lng = it.lng,
                        accuracyM = it.accuracyM,
                        batteryPct = it.batteryPct,
                        recordedAt = it.recordedAt,
                    )
                },
            )
            db.locationDao().markSynced(batch.map { it.localId }, Instant.now().toString())
            uploaded += batch.size
        }
        uploaded
    }

    override suspend fun refreshLatest(childId: String): AppResult<LocationFix?> = runApp {
        val rows = client.postgrest.from("locations")
            .select {
                filter { eq("child_id", childId) }
                order("recorded_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(1L)
            }
            .decodeList<LocationDto>()
        rows.firstOrNull()?.toDomain()?.also { fix ->
            // Cache as already-synced so the parent sees the last fix offline.
            db.locationDao().enqueue(fix.toEntity(syncedAt = Instant.now().toString()))
        }
    }
}
