package com.familyguard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.familyguard.core.ui.theme.FamilyGuardTheme
import com.familyguard.ui.FamilyGuardRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * FragmentActivity (not ComponentActivity) so feature:settings can host a
 * BiometricPrompt for the parent gate (PLAN P6/P18).
 *
 * The block screen's "Ask for more time" deep link is a child-app concern and
 * lives in `:app-child` (PLAN-SPLIT §2).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamilyGuardTheme {
                FamilyGuardRoot()
            }
        }
    }
}
