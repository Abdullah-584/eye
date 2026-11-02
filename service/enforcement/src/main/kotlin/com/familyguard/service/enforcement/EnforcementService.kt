package com.familyguard.service.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.familyguard.core.data.mode.ChildDeviceRepository
import com.familyguard.core.data.sync.InventoryWorker
import com.familyguard.core.data.sync.SyncEngine
import com.familyguard.core.domain.repo.AdminEventRepository
import com.familyguard.core.domain.repo.AppBlockRepository
import com.familyguard.core.domain.repo.AppInventoryRepository
import com.familyguard.core.domain.repo.UninstallRequestRepository
import com.familyguard.core.domain.enforcement.AppLimitView
import com.familyguard.core.domain.enforcement.BlockReason
import com.familyguard.core.domain.enforcement.EnforcementDecision
import com.familyguard.core.domain.enforcement.EnforcementEngine
import com.familyguard.core.domain.enforcement.ScheduleEvaluator
import com.familyguard.core.domain.enforcement.ScheduleView
import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.ScheduleAction
import com.familyguard.core.domain.model.UsageDaily
import com.familyguard.core.domain.model.UsageEvent
import com.familyguard.core.domain.model.UsageEventType
import com.familyguard.core.domain.repo.PairingRepository
import com.familyguard.core.domain.repo.RulesRepository
import com.familyguard.core.domain.repo.UsageRepository
import com.familyguard.feature.enforcement.BlockActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** M5 — the child-device enforcement loop.
 *
 *  1. every ~1 s: find the foreground package (UsageStats polling, deliberately
 *     not an AccessibilityService),
 *  2. feed today's usage + active schedules + per-app limits into the pure
 *     [EnforcementEngine] (unit-tested in :core:domain),
 *  3. on a blocking decision, raise [BlockActivity] over the offending app,
 *  4. record usage segments and flush the upload queue,
 *  5. heartbeat every 60 s so the parent's "device not reporting" card can
 *     tell the truth (PLAN §7.5: stale > 15 min),
 *  6. M2a: sample the device-admin state every 5 s; on a transition, report
 *     it as an `admin_events` row. We never re-activate the admin ourselves
 *     and never obstruct the OS deactivation path — observe, report, tell
 *     the truth in the notification.
 *
 * Runs only while this install is paired to a child; an unpaired install
 * stops itself the moment it is started.
 */
@AndroidEntryPoint
class EnforcementService : LifecycleService() {

    @Inject lateinit var childDevice: ChildDeviceRepository
    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var usageRepository: UsageRepository
    @Inject lateinit var pairingRepository: PairingRepository
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var adminEvents: AdminEventRepository
    @Inject lateinit var appBlocks: AppBlockRepository
    @Inject lateinit var appInventory: AppInventoryRepository
    @Inject lateinit var uninstallRequestRepo: UninstallRequestRepository
    @Inject lateinit var db: com.familyguard.core.database.FamilyGuardDatabase

    private val serviceScope = CoroutineScope(SupervisorJob())

    private var childId: String? = null
    private var deviceId: String? = null
    private var jobsStarted = false

    @Volatile private var limits: List<AppLimit> = emptyList()
    @Volatile private var schedules: List<Schedule> = emptyList()
    @Volatile private var usedToday: Map<String, Long> = emptyMap()
    @Volatile private var screenOn: Boolean = true

    // M2b: the parent's block set, observed from Room. Fed straight into the
    // one EnforcementEngine.evaluate call — no second blocking path.
    @Volatile private var blockedApps: Set<String> = emptySet()

    private var lastPackage: String? = null
    private var segmentStartMs: Long = System.currentTimeMillis()
    private var blockingPackage: String? = null

    /** True/false once measured; null before the first admin sample. */
    @Volatile private var adminActive: Boolean? = null

    private lateinit var usageStats: UsageStatsManager

    // M2b: live install/remove detection. A runtime receiver inside this
    // foreground service gets all three actions regardless of API level.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> serviceScope.launch {
                    runCatching { appInventory.reportScan() }
                }
                // M2c: handle system uninstall result for pending requests.
                // Only mark as done if the request status is 'confirmed' (child
                // confirmed the prompt before launching the system dialog).
                Intent.ACTION_PACKAGE_REMOVED -> {
                    val packageName = intent?.data?.getEncodedStringExtra("package") ?: return
                    // Find the matching uninstall request and mark it done
                    db.getForChild(childId!!).firstOrNull {
                        it.packageName == packageName && it.status == "confirmed"
                    }?.let { request ->
                        // ACTION_PACKAGE_REMOVED fired, mark request as done
                        uninstallRequestRepo.decide(request.id, true).onSuccess {
                            // Refresh the local cache after success
                        }.getOrNull()
                    }
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    serviceScope.launch { flushSegment() }
                    screenOn = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    segmentStartMs = System.currentTimeMillis()
                    lastPackage = null
                    screenOn = true
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createChannel()
        startAsForeground()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        // M2b: package add/remove/replace, filtered to the "package" scheme.
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
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
            // No mode flag any more: paired or not, and paired is the only
            // reason this service exists.
            val child = childDevice.childIdOrNull()
            val device = childDevice.deviceIdOrNull()
            if (child == null || device == null) {
                stopSelf()
                return@launch
            }
            childId = child
            deviceId = device

            // Live rules + time-request decisions (PLAN §3.3 fast path):
            // postgres_changes → SyncEngine → Room → this loop, so a parent
            // edit lands on the child device within seconds instead of the
            // next 60 s maintenance refresh.
            syncEngine.startChildSync(child)

            launch { rulesRepository.observeLimits(child).collect { limits = it } }
            launch { rulesRepository.observeSchedules(child).collect { schedules = it } }
            // M2b: observe the block set; the enforcement loop reads the
            // latest value every tick. Unblock drops the package immediately.
            launch { appBlocks.observe(child).collect { rows ->
                blockedApps = rows.filter { it.blocked }.map { it.packageName }.toSet()
            } }
            launch {
                rulesRepository.refresh(child)
                usageRepository.observeUsage(child).collect { rows -> usedToday = toUsedToday(rows) }
            }
            // M2b: first inventory report + the 6 h backstop. The live
            // receiver handles the rest while this service runs.
            launch {
                InventoryWorker.schedule(this@EnforcementService)
                runCatching { appInventory.reportScan() }
            }
            launch { tickLoop() }
            launch { maintenanceLoop() }
            launch { adminWatchLoop(child, device) }
        }
    }

    /** PLAN M5: poll the foreground app every second while a rule can bite. */
    private suspend fun tickLoop() {
        while (true) {
            delay(TICK_MS)
            val zone = ZoneId.systemDefault()
            val now = Instant.now()

            val foreground = if (screenOn) foregroundPackage() else null

            // Close out the usage segment of the package we are leaving.
            if (foreground != lastPackage) {
                flushSegment()
                lastPackage = foreground
                segmentStartMs = System.currentTimeMillis()
            } else if (foreground != null && System.currentTimeMillis() - segmentStartMs >= SEGMENT_MS) {
                flushSegment()
                segmentStartMs = System.currentTimeMillis()
            }

            if (foreground == null || foreground == packageName) continue

            val activeSchedules = schedules
                .filter {
                    it.action == ScheduleAction.LOCK &&
                        ScheduleEvaluator.isActive(it.view(), now, zone)
                }
                .map { it.view() }

            val decision = EnforcementEngine.evaluate(
                foregroundPackage = foreground,
                limits = limits.map { it.view() },
                usedSecondsToday = usedToday,
                activeSchedules = activeSchedules,
                blockedApps = blockedApps,
                now = now,
                zone = zone,
            )

            if (decision.blockedPackage != null) {
                if (blockingPackage != decision.blockedPackage) {
                    blockingPackage = decision.blockedPackage
                    startActivity(
                        BlockActivity.intent(
                            context = this@EnforcementService,
                            packageName = decision.blockedPackage,
                            reason = decision.reason,
                            usedSeconds = decision.usedSeconds,
                            limitSeconds = decision.limitSeconds,
                            scheduleName = decision.scheduleName,
                            nextFreeAtMs = nextFreeAt(decision, now, zone),
                        )
                    )
                }
            } else {
                blockingPackage = null
            }
        }
    }

    /** M2a device-admin sampling. Baseline comes from the store, not from
     * memory, so "activate on day 5, deactivate on day 9, app restarts on
     * day 10" still reports the deactivation. Never re-activates; the row
     * RLS leaves solely to the paired device (see the migration).
     */
    private suspend fun adminWatchLoop(childId: String, deviceId: String) {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, FamilyGuardDeviceAdminReceiver::class.java)
        while (true) {
            val active = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
            val previous = childDevice.adminActiveOrNull()
            if (AdminStateTransition.shouldReport(previous, active)) {
                runCatching { adminEvents.report(childId, deviceId, active) }
            }
            if (previous != active) {
                runCatching { childDevice.setDeviceAdminActive(active) }
                adminActive = active
                updateNotification()
            }
            delay(ADMIN_SAMPLE_MS)
        }
    }

    /** Upload flush, heartbeat and the notification's live rule count. */
    private suspend fun maintenanceLoop() {
        while (true) {
            delay(MAINTENANCE_MS)
            updateNotification()
            usageRepository.flushUploads()
            // M2b belt-and-braces: a child offline when the parent toggled
            // catches up here within one maintenance cycle (<=60 s).
            childId?.let { runCatching { appBlocks.refresh(it) } }
            pairingRepository.heartbeat(
                fcmToken = null,
                appVersion = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "unknown",
                sdk = Build.VERSION.SDK_INT,
            )
        }
    }

    /** Emits the accumulated seconds for the segment we are closing. */
    private suspend fun flushSegment() {
        val pkg = lastPackage ?: return
        val child = childId ?: return
        val device = deviceId ?: return
        val durationMs = System.currentTimeMillis() - segmentStartMs
        if (durationMs < MIN_SEGMENT_MS) return
        usageRepository.recordUsage(
            UsageEvent(
                childId = child,
                deviceId = device,
                packageName = pkg,
                eventType = UsageEventType.APP_SWITCH,
                startedAt = Instant.ofEpochMilli(segmentStartMs).toString(),
                durationSec = (durationMs / 1000).toInt(),
            ),
        )
    }

    private fun toUsedToday(rows: List<UsageDaily>): Map<String, Long> {
        val today = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return rows.filter { it.day == today }.associate { it.packageName to it.seconds }
    }

    /** Last ACTIVITY_RESUMED event in the recent window, or null. */
    private fun foregroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - LOOKBACK_MS, now)
        var current: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                current = event.packageName
            }
        }
        return current
    }

    /** Schedule end → next activation; limit end → local midnight. */
    private fun nextFreeAt(decision: EnforcementDecision, now: Instant, zone: ZoneId): Long? {
        when (decision.reason) {
            BlockReason.SCHEDULE -> {
                val active = schedules.firstOrNull { it.name == decision.scheduleName }?.view()
                active?.let { ScheduleEvaluator.nextActivation(it, now, zone) }?.toEpochMilli()
            }
            BlockReason.LIMIT_REACHED -> now.atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            else -> null
        }
    }

    private fun startAsForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_enforcement),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_enforcement_desc)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val activeRules: Int = limits.count { it.enabled } + schedules.count { it.enabled }
        // M2a: while device admin is off, the persistent indicator says so —
        // "Parental controls active" would be a lie (M2-PLAN §1.3).
        val text = if (adminActive == false) {
            getString(R.string.notif_admin_off_text)
        } else {
            getString(R.string.notif_enforcement_text, activeRules)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(getString(R.string.notif_enforcement_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        syncEngine.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun AppLimit.view() = AppLimitView(
        packageName = packageName,
        dailyLimitSeconds = dailyLimitSeconds,
        enabled = enabled,
    )

    private fun Schedule.view() = ScheduleView(
        name = name,
        daysMask = daysMask,
        startTime = startTime,
        endTime = endTime,
        enabled = enabled,
    )

    companion object {
        private const val CHANNEL_ID = "enforcement"
        private const val NOTIFICATION_ID = 41
        private const val TICK_MS = 1_000L
        private const val ADMIN_SAMPLE_MS = 5_000L
        private const val MAINTENANCE_MS = 60_000L
        private const val SEGMENT_MS = 60_000L
        private const val MIN_SEGMENT_MS = 5_000L
        private const val LOOKBACK_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EnforcementService::class.java),
            )
        }
    }
}