package com.familyguard.service.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.familyguard.core.data.sync.InventoryWorker

/**
 * M2b — the cold-start-safe inventory backstop (docs/M2b-PLAN.md §2.2 layer 2).
 *
 * `ACTION_PACKAGE_FULLY_REMOVED` IS on Android 8+'s implicit-broadcast
 * exemption list (unlike `PACKAGE_ADDED`, which is why the live detection
 * lives in a runtime receiver inside the foreground service). This manifest
 * receiver is the belt-and-braces path: if the service was force-stopped when
 * an app was fully removed, this still fires and enqueues a one-shot reconcile
 * so the row is marked `removed_at` and the parent list converges.
 *
 * It does no network itself (a receiver's window is ~10 s); it hands the work
 * to [InventoryWorker].
 */
class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<InventoryWorker>().build(),
        )
    }
}
