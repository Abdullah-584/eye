package com.familyguard.feature.enforcement

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.familyguard.core.domain.enforcement.BlockReason
import com.familyguard.core.ui.theme.FamilyGuardTheme
import java.time.Instant

/**
 * Full-screen host for [BlockScreen], launched by service:enforcement when
 * the foreground app must be blocked (PLAN M5/C6). `noHistory` means leaving
 * the app (Home, app switch) dismisses it — the next violation re-shows it.
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val reason = runCatching {
            BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON) ?: BlockReason.NONE.name)
        }.getOrDefault(BlockReason.NONE)
        val used = intent.getLongExtra(EXTRA_USED, 0L)
        val limit = intent.getIntExtra(EXTRA_LIMIT, 0)
        val schedule = intent.getStringExtra(EXTRA_SCHEDULE)
        val nextFreeMs = intent.getLongExtra(EXTRA_NEXT_FREE, 0L)
            .takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it) }

        setContent {
            FamilyGuardTheme {
                BlockScreen(
                    reason = reason,
                    appLabel = resolveLabel(pkg),
                    usedSeconds = used,
                    limitSeconds = limit,
                    scheduleName = schedule,
                    nextFreeAt = nextFreeMs,
                    onRequestTime = { openRequestScreen() },
                    onBackHome = { finish() },
                )
            }
        }
    }

    private fun resolveLabel(pkg: String?): String? = pkg?.let {
        runCatching {
            val info = packageManager.getApplicationInfo(it, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    /** "Ask for more time" → child home with the request screen open. */
    private fun openRequestScreen() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra(EXTRA_OPEN_REQUESTS, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (intent != null) startActivity(intent)
        finish()
    }

    companion object {
        /** Read by the app shell to land on the child's request screen. */
        const val EXTRA_OPEN_REQUESTS = "com.familyguard.extra.OPEN_REQUESTS"

        private const val EXTRA_PACKAGE = "block_package"
        private const val EXTRA_REASON = "block_reason"
        private const val EXTRA_USED = "block_used"
        private const val EXTRA_LIMIT = "block_limit"
        private const val EXTRA_SCHEDULE = "block_schedule"
        private const val EXTRA_NEXT_FREE = "block_next_free"

        /** Built by service:enforcement on every blocking decision. */
        fun intent(
            context: Context,
            packageName: String?,
            reason: BlockReason,
            usedSeconds: Long,
            limitSeconds: Int,
            scheduleName: String?,
            nextFreeAtMs: Long?,
        ): Intent = Intent(context, BlockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_REASON, reason.name)
            putExtra(EXTRA_USED, usedSeconds)
            putExtra(EXTRA_LIMIT, limitSeconds)
            putExtra(EXTRA_SCHEDULE, scheduleName)
            nextFreeAtMs?.let { putExtra(EXTRA_NEXT_FREE, it) }
        }
    }
}
