package com.familyguard.core.data.inventory

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Telephony
import android.telecom.TelecomManager
import com.familyguard.core.domain.inventory.AppVisibility
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One launcher-visible app as the scanner found it, before it becomes a row.
 */
data class ScannedApp(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    val versionName: String?,
    val iconSha: String?,
)

/**
 * M2b — the child-side query (docs/M2b-PLAN.md §1.1).
 *
 * Source of truth is `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`
 * — *not* `getInstalledPackages()`. That set is exactly "apps a child can
 * open", already deduplicated per package, and already package-visibility
 * legal under the `<queries>` declaration in the app-child manifest (no
 * `QUERY_ALL_PACKAGES`, which would trigger Play policy review).
 *
 * The [AppVisibility] filter then drops the hard-exclusion safety list so the
 * parent never sees a toggle that cannot work (Settings, SystemUI, dialer,
 * SMS, package installers, our own package).
 */
@Singleton
class AppScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Scan + filter. Runs on Dispatchers.IO (callers must be off the main thread). */
    fun scan(): List<ScannedApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcher, 0)

        val own = context.packageName
        val dialer = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        val sms = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 19) {
                Telephony.Sms.getDefaultSmsPackage(context)
            } else {
                null
            }
        }.getOrNull()

        val candidates = resolved
            .map { it.activityInfo }
            .filter { it != null }
            .distinctBy { it.packageName }

        val allowed = AppVisibility.filter(
            candidates = candidates.map { it.packageName },
            ownPackage = own,
            defaultDialerPackage = dialer,
            defaultSmsPackage = sms,
        ).toSet()

        return candidates
            .filter { it.packageName in allowed }
            .mapNotNull { info ->
                val pkg = info.packageName
                val appInfo = info.applicationInfo
                ScannedApp(
                    packageName = pkg,
                    appName = labelOf(appInfo ?: return@mapNotNull null, pm),
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    versionName = runCatching {
                        pm.getPackageInfo(pkg, 0).versionName
                    }.getOrNull(),
                    iconSha = iconShaOf(appInfo, pm),
                )
            }
    }

    private fun labelOf(info: ApplicationInfo, pm: PackageManager): String =
        runCatching { pm.getApplicationLabel(info).toString().take(MAX_LABEL) }
            .getOrDefault(info.packageName)

    /** SHA-256 (hex) of the launcher icon rendered small; never the bytes. */
    private fun iconShaOf(info: ApplicationInfo, pm: PackageManager): String? =
        runCatching {
            val drawable = pm.getApplicationIcon(info)
            val size = ICON_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            bitmap.recycle()
            sha256Hex(out.toByteArray())
        }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_LABEL = 120
        const val ICON_PX = 96
    }
}
