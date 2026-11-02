package com.familyguard.core.data.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.data.dto.InstalledAppDto
import com.familyguard.core.data.internal.runApp
import com.familyguard.core.data.internal.toDomain
import com.familyguard.core.data.internal.toEntity
import com.familyguard.core.data.inventory.AppScanner
import com.familyguard.core.data.mode.ChildDeviceRepository
import com.familyguard.core.database.FamilyGuardDatabase
import com.familyguard.core.domain.inventory.InventoryDiff
import com.familyguard.core.domain.model.InstalledApp
import com.familyguard.core.domain.repo.AppInventoryRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M2b — `installed_apps`: the paired device reports what it can launch; the
 * parent reads it. The write path here is the ONLY one (RLS restricts inserts
 * to the device's own child+device row); the parent's [refresh] is a read.
 *
 * Every detection layer — live package receiver, manifest backstop, the 6 h
 * worker and cold start — funnels through [reportScan], which reduces to the
 * pure [InventoryDiff.reconcile] so an event burst and a periodic scan
 * produce identical output (docs/M2b-PLAN.md §2.2).
 */
@Singleton
class AppInventoryRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val db: FamilyGuardDatabase,
    private val scanner: AppScanner,
    private val childDevice: ChildDeviceRepository,
) : AppInventoryRepository {

    /** Child-side: scan → reconcile → write Room → upload the delta. */
    override suspend fun reportScan(): AppResult<Int> = runApp {
        val childId = childDevice.childIdOrNull()
            ?: error("not paired: no childId")
        val deviceId = childDevice.deviceIdOrNull()
            ?: error("not paired: no deviceId")

        val scan = withContext(Dispatchers.IO) { scanner.scan() }
        val now = Instant.now().toString()

        val previous = db.installedAppDao().getForDevice(deviceId).map {
            InventoryDiff.Previous(it.packageName, it.firstSeen, it.removedAt)
        }
        val result = InventoryDiff.reconcile(previous, scan.map { it.packageName }, now)
        val firstSeenByPkg = result.upserts.associate { it.packageName to it.firstSeen }

        // Room: refresh live rows (first_seen preserved by the diff) ...
        val scanByPkg = scan.associateBy { it.packageName }
        val liveRows = scan.map { app ->
            app.toEntity(childId, deviceId, firstSeenByPkg[app.packageName] ?: now, now)
        }
        db.installedAppDao().upsertAll(liveRows)
        // ... and soft-delete the ones the scan no longer sees.
        if (result.softDeletes.isNotEmpty()) {
            db.installedAppDao().markRemoved(deviceId, result.softDeletes, now)
        }

        // Upload: one upsert for the live set, one update for the soft-deletes.
        if (liveRows.isNotEmpty()) {
            val dtos = scan.map {
                it.toDto(childId, deviceId, firstSeenByPkg[it.packageName] ?: now, now)
            }
            client.postgrest.from("installed_apps")
                .upsert(dtos) { onConflict = "device_id,package_name" }
        }
        if (result.softDeletes.isNotEmpty()) {
            client.postgrest.from("installed_apps").update(
                { set("removed_at", now) },
                {
                    filter {
                        eq("device_id", deviceId)
                        isIn("package_name", result.softDeletes)
                    }
                },
            )
        }
        liveRows.size
    }

    /** Parent-side: observe the cached inventory for a child. */
    override fun observe(childId: String): Flow<List<InstalledApp>> =
        db.installedAppDao().observe(childId).map { list -> list.map { it.toDomain() } }

    /** Parent-side pull: refresh the cache from `installed_apps` (RLS-scoped). */
    override suspend fun refresh(childId: String): AppResult<Int> = runApp {
        val rows = client.postgrest.from("installed_apps")
            .select { filter { eq("child_id", childId) } }
            .decodeList<InstalledAppDto>()
        db.installedAppDao().upsertAll(rows.map { it.toEntity() })
        rows.size
    }
}

// Scanner output → Room entity / wire DTO. first_seen is carried by the diff.
private fun com.familyguard.core.data.inventory.ScannedApp.toEntity(
    childId: String,
    deviceId: String,
    firstSeen: String,
    lastSeen: String,
) = com.familyguard.core.database.entity.InstalledAppEntity(
    childId = childId,
    deviceId = deviceId,
    packageName = packageName,
    appName = appName,
    isSystem = isSystem,
    versionName = versionName,
    launchable = true,
    iconSha = iconSha,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    removedAt = null,
)

private fun com.familyguard.core.data.inventory.ScannedApp.toDto(
    childId: String,
    deviceId: String,
    firstSeen: String,
    lastSeen: String,
) = InstalledAppDto(
    childId = childId,
    deviceId = deviceId,
    packageName = packageName,
    appName = appName,
    isSystem = isSystem,
    versionName = versionName,
    launchable = true,
    iconSha = iconSha,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    removedAt = null,
)
