package com.familyguard.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The special-access + runtime permissions the walkthrough manages (PLAN §5). */
enum class PermissionKind {
    USAGE_ACCESS,
    OVERLAY,
    FINE_LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
    BATTERY_UNRESTRICTED,

    /** M2a: the device admin is active ("Parental controls active"). */
    DEVICE_ADMIN,
}

data class PermissionState(
    val kind: PermissionKind,
    val granted: Boolean,
    /** Extra nuance: e.g. background location denied while foreground works. */
    val degraded: Boolean = false,
)

/**
 * Checks + deep-links for every permission, implemented against the real
 * system services so the parent's "device health" cards (PLAN §7.5) show the
 * truth from the device itself.
 */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun check(kind: PermissionKind): PermissionState = when (kind) {
        PermissionKind.USAGE_ACCESS -> PermissionState(kind, hasUsageAccess())
        PermissionKind.OVERLAY -> PermissionState(kind, Settings.canDrawOverlays(context))
        PermissionKind.FINE_LOCATION -> PermissionState(
            kind,
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
        PermissionKind.BACKGROUND_LOCATION -> {
            val bg = Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            val fg = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            PermissionState(kind, granted = bg, degraded = fg && !bg)
        }
        PermissionKind.NOTIFICATIONS -> PermissionState(
            kind,
            (context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false),
        )
        PermissionKind.BATTERY_UNRESTRICTED -> {
            val pm = context.getSystemService(PowerManager::class.java)
            PermissionState(kind, pm?.isIgnoringBatteryOptimizations(context.packageName) == true)
        }
        PermissionKind.DEVICE_ADMIN -> {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            PermissionState(kind, dpm?.isAdminActive(adminComponent(context)) == true)
        }
    }

    /** Settings deep-link for the step (PLAN §5.1). */
    fun settingsIntent(kind: PermissionKind): Intent = when (kind) {
        PermissionKind.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        PermissionKind.OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        PermissionKind.FINE_LOCATION ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        PermissionKind.BACKGROUND_LOCATION ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        PermissionKind.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        PermissionKind.BATTERY_UNRESTRICTED ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        // Launches the SYSTEM activation dialog. Nothing here activates in
        // the background; the child taps "Activate" themselves (M2 §2.1).
        PermissionKind.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        /**
         * M2a — the receiver this app declares lives in :service:enforcement
         * (referenced by class name so :core:permissions stays free of an
         * implementation dependency; CI asserts the two names agree).
         */
        const val ADMIN_RECEIVER_CLASSNAME =
            "com.familyguard.service.enforcement.FamilyGuardDeviceAdminReceiver"

        fun adminComponent(context: Context) =
            ComponentName(context.packageName, ADMIN_RECEIVER_CLASSNAME)

        /**
         * Order the child device walks at M2a (M2-PLAN §1.2): usage access
         * feeds everything downstream, the rest follow the plan's rationale,
         * device admin last (the strongest ask, explained truthfully). The
         * BACKGROUND step covers fine location first in the walkthrough UI.
         * No longer carries BATTERY_UNRESTRICTED: the child-facing walkthrough
         * is exactly the plan's five steps; the check stays for the parent.
         */
        val CHILD_STEPS = listOf(
            PermissionKind.USAGE_ACCESS,
            PermissionKind.NOTIFICATIONS,
            PermissionKind.OVERLAY,
            PermissionKind.BACKGROUND_LOCATION,
            PermissionKind.DEVICE_ADMIN,
        )

        /** Order for the parent's own dashboard helpers. */
        val PARENT_STEPS = listOf(
            PermissionKind.NOTIFICATIONS,
            PermissionKind.BATTERY_UNRESTRICTED,
        )
    }
}
