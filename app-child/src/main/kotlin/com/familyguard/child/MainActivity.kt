package com.familyguard.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.familyguard.child.ui.ChildRoot
import com.familyguard.core.ui.theme.FamilyGuardTheme
import com.familyguard.feature.enforcement.BlockActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * The child app's single activity.
 *
 * Not a FragmentActivity: the parent-only biometric gate lives in
 * `:app-child`'s counterpart, and this app has no PIN surface to protect.
 *
 * Honors the block screen's "Ask for more time" deep link: the child lands
 * directly on the request screen of the hub.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var openChildRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openChildRequest = readOpenRequests(intent)
        enableEdgeToEdge()
        setContent {
            FamilyGuardTheme {
                ChildRoot(
                    onOpenChildRequest = {
                        val open = openChildRequest
                        openChildRequest = false
                        open
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (readOpenRequests(intent)) openChildRequest = true
    }

    private fun readOpenRequests(intent: Intent?): Boolean =
        intent?.getBooleanExtra(BlockActivity.EXTRA_OPEN_REQUESTS, false) == true
}
