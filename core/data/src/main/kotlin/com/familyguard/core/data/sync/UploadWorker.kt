package com.familyguard.core.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familyguard.core.domain.repo.LocationRepository
import com.familyguard.core.domain.repo.UsageRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Drains the queued usage/location uploads (PLAN §4.6): the hot path only
 * writes Room; the network happens here, every 15 minutes (WorkManager's
 * minimum), plus whatever explicit flushes services trigger on screen-off.
 *
 * The worker uses an Hilt [EntryPoint] instead of @HiltWorker to avoid an
 * extra artifact; dependencies are repository interfaces, already singletons.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun usageRepository(): UsageRepository
        fun locationRepository(): LocationRepository
    }

    override suspend fun doWork(): Result = try {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        deps.usageRepository().flushUploads()
        deps.locationRepository().flushUploads()
        Result.success()
    } catch (t: Throwable) {
        // WorkManager retries with backoff; queued rows stay in Room untouched.
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "fg-upload"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
