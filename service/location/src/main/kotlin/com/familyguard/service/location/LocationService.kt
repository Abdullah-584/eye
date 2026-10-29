package com.familyguard.service.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.familyguard.core.data.mode.AppMode
import com.familyguard.core.data.mode.ModeRepository
import com.familyguard.core.domain.model.LocationFix
import com.familyguard.core.domain.repo.LocationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * PLAN P13 — the location foreground service: a plain `LocationManager`
 * listener (no play-services dependency) that records a fix every ~60 s /
 * 50 m while the device is in CHILD mode, feeds the offline queue and
 * flushes periodically. The child-mode walkthrough explains this service in
 * the prominent disclosure before the permission is requested.
 */
@AndroidEntryPoint
class LocationService : LifecycleService() {

    @Inject lateinit var modeRepository: ModeRepository
    @Inject lateinit var locationRepository: LocationRepository

    private val serviceScope = CoroutineScope(SupervisorJob())

    private var childId: String? = null
    private var deviceId: String? = null
    private var fixCount = 0
    private var batteryPct: Int? = null

    private lateinit var locationManager: LocationManager

    private val listener = LocationListener { location ->
        serviceScope.launch { record(location) }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra("level", -1) ?: -1
            val scale = intent?.getIntExtra("scale", -1) ?: -1
            if (level >= 0 && scale > 0) batteryPct = (level * 100) / scale
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        startAsForeground()
        runCatching {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        super.onStartCommand(intent, flags, id)
        startAsForeground()
        startJobs()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun startJobs() {
        if (jobsStarted) return
        jobsStarted = true

        serviceScope.launch {
            if (modeRepository.currentMode() != AppMode.CHILD) {
                stopSelf()
                return@launch
            }
            val child = modeRepository.childIdOrNull()
            val device = modeRepository.deviceIdOrNull()
            if (child == null || device == null) {
                stopSelf()
                return@launch
            }
            childId = child
            deviceId = device

            if (!hasForegroundLocation()) {
                // Nothing to do until the walkthrough grants the permission.
                stopSelf()
                return@launch
            }

            requestUpdates()
            launch {
                while (true) {
                    delay(FLUSH_MS)
                    locationRepository.flushUploads()
                }
            }
        }
    }

    private var jobsStarted = false

    private fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestUpdates() {
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
            )
        }
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
            )
        }
        runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last ->
                serviceScope.launch { record(last) }
            }
        }
    }

    private suspend fun record(location: Location) {
        val child = childId ?: return
        val device = deviceId ?: return
        locationRepository.recordFix(
            LocationFix(
                childId = child,
                deviceId = device,
                lat = location.latitude,
                lng = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                batteryPct = batteryPct,
                recordedAt = Instant.now().toString(),
            ),
        )
        fixCount++
        if (fixCount % FLUSH_EVERY_FIXES == 0) {
            locationRepository.flushUploads()
        }
    }

    private fun startAsForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_location),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_location_desc)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_location_title))
            .setContentText(getString(R.string.notif_location_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(listener) }
        runCatching { unregisterReceiver(batteryReceiver) }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "location"
        private const val NOTIFICATION_ID = 42
        private const val MIN_TIME_MS = 60_000L
        private const val MIN_DISTANCE_M = 50f
        private const val FLUSH_MS = 5 * 60 * 1000L
        private const val FLUSH_EVERY_FIXES = 10

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java),
            )
        }
    }
}
