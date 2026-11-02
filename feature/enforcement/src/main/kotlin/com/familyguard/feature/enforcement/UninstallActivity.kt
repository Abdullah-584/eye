package com.familyguard.feature.enforcement

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.familyguard.core.ui.theme.FamilyGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Launched by EnforcementService when a child confirms an uninstall request.
 * Shows a prompt and launches the system uninstall confirmation dialog.
 *
 * After launching the system dialog, the enforcement service's packageReceiver
 * picks up ACTION_PACKAGE_REMOVED and marks the request as done or declined
 * based on a 45-second timeout.
 */
@AndroidEntryPoint
class UninstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FamilyGuardTheme {
                UninstallPromptScreen(
                    onConfirm = { launchSystemUninstall() },
                    onDismiss = { finish() }
                )
            }
        }
    }

    /** Launch the native Android system uninstall confirmation dialog. */
    private fun launchSystemUninstall() {
        val deleteIntent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(deleteIntent)
        // The system dialog result is captured via ACTION_PACKAGE_REMOVED
        // in the EnforcementService's packageReceiver, not via onActivityResult.
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "uninstall_package_name"

        @JvmStatic
        fun start(context: Context, packageName: String) {
            val intent = Intent(context, UninstallActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            context.startActivity(intent)
        }
    }
}

/** M2c: uninstal prompt UI shown to the child. */
@Composable
fun UninstallPromptScreen(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    packageName: String?,
) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Your parent is asking to remove:\n${packageName ?: "this app"}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "This will remove the app from your device.\nThis action will be reported to your parent.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { onDismiss(); scope.cancel() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    onConfirm()
                    // Launch the system uninstall dialog
                    UninstallActivity.start(applicationContext, packageName!!
                    )
                    scope.cancel()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Confirm")
            }
        }
    }
}