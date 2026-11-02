package com.familyguard.core.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familyguard.core.domain.repo.AppInventoryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * M2b — the cold-start-safe inventory backstop (docs/M2b-PLAN.md §2.2 layer 3).
 *
 * The live package receiver catches install/remove while the enforcement
 * service runs; this 6 h periodic scan is the belt-and-braces reconcile that
 * converges whatever the receiver missed (Doze, force-stop, OEM kills). It
 * also runs once on child cold start and right after pairing. All three
 * paths reduce to the same pure [com.familyguard.core.domain.inventory.InventoryDiff].
 *
 * Uses an Hilt [EntryPoint] like [UploadWorker] — no extra artifact.
 */
class InventoryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun appInventoryRepository(): AppInventoryRepository
    }

    override suspend fun doWork(): Result = try {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        // Unpaired install: nothing to report; succeed so it does not retry.
        deps.appInventoryRepository().reportScan()
        Result.success()
    } catch (t: Throwable) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "fg-inventory"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InventoryWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
