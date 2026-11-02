package com.familyguard.core.domain.repo

import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.AppBlock
import com.familyguard.core.domain.model.InstalledApp
import kotlinx.coroutines.flow.Flow

/**
 * M2b — installed-app inventory (docs/M2b-PLAN.md §1/§2).
 *
 * The device *reports* what it can launch; the parent reads it. RLS is the
 * authority (the device may only write its own child+device rows, the parent
 * has no write path at all) — this interface never widens that.
 */
interface AppInventoryRepository {

    /**
     * Child-side: scan the launcher-visible apps, reconcile against the local
     * cache ([InventoryDiff]), and upload the delta. Called on cold start,
     * after pairing, on package add/remove, and every 6 h (§2.2).
     */
    suspend fun reportScan(): AppResult<Int>

    /** Parent-side (or child re-read): observe the cached inventory for a child. */
    fun observe(childId: String): Flow<List<InstalledApp>>

    /** Parent-side pull: refresh the cache from `installed_apps`. */
    suspend fun refresh(childId: String): AppResult<Int>
}

/**
 * M2b — the parent's block intent (docs/M2b-PLAN.md §3).
 *
 * Parent writes, device reads. The child can never set or clear `blocked`
 * (RLS has no device write policy), so there is no self-unblock path.
 */
interface AppBlockRepository {

    /** Observe the block set for a child (enforcement reads this). */
    fun observe(childId: String): Flow<List<AppBlock>>

    /** Pull `blocked_apps` into the local cache (reconnect / maintenance). */
    suspend fun refresh(childId: String): AppResult<Int>

    /** Parent toggle: upsert `(childId, packageName)` with [blocked]. */
    suspend fun setBlocked(childId: String, packageName: String, blocked: Boolean): AppResult<Unit>
}
