package com.familyguard.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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
    private val context: Context,
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
        /** Order the child device walks during pairing (PLAN C7). */
        val CHILD_STEPS = listOf(
            PermissionKind.NOTIFICATIONS,
            PermissionKind.USAGE_ACCESS,
            PermissionKind.OVERLAY,
            PermissionKind.FINE_LOCATION,
            PermissionKind.BATTERY_UNRESTRICTED,
        )

        /** Order for the parent's own dashboard helpers. */
        val PARENT_STEPS = listOf(
            PermissionKind.NOTIFICATIONS,
            PermissionKind.BATTERY_UNRESTRICTED,
        )
    }
}
