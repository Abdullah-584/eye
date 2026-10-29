package com.familyguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.familyguard.core.ui.theme.FamilyGuardTheme
import com.familyguard.feature.enforcement.BlockActivity
import com.familyguard.ui.FamilyGuardRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * FragmentActivity (not ComponentActivity) so feature:settings can host a
 * BiometricPrompt for the parent gate (PLAN P6/P18).
 *
 * Also honors the block screen's "Ask for more time" deep link: the child
 * lands directly on the request screen of the child hub.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private var openChildRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openChildRequest = readOpenRequests(intent)
        enableEdgeToEdge()
        setContent {
            FamilyGuardTheme {
                FamilyGuardRoot(
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
