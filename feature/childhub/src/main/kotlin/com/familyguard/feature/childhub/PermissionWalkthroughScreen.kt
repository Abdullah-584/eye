package com.familyguard.feature.childhub

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyguard.core.permissions.PermissionKind

/**
 * M2a — one screen per permission, in [PermissionChecker.CHILD_STEPS] order
 * (M2-PLAN §1.2), grant-state rechecked on every lifecycle resume, and a
 * truth-telling checklist as the finish line.
 */
@Composable
fun PermissionWalkthroughScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WalkthroughViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The grant can only take effect somewhere else — the runtime dialog's
    // result callback, or the moment we come back from the system Settings
    // screen. ON_RESUME is the deterministic moment to query the system.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.recheck() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.walkthrough_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        val activeIndex = state.activeIndex
        if (activeIndex < state.steps.size) {
            val step = state.steps[activeIndex]

            Text(
                text = stringResource(
                    R.string.walkthrough_progress,
                    activeIndex + 1,
                    state.steps.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))

            StepCard(
                kind = step.kind,
                fineGranted = state.fineGranted,
                onOpenSettings = {
                    runCatching { context.startActivity(viewModel.settingsIntent(step.kind)) }
                },
                onRequestFineLocation = {
                    // android.Manifest.permission.ACCESS_FINE_LOCATION is
                    // declared by :service:location, merged into :app-child.
                    runCatching {
                        fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
            )

            if (WalkthroughFlow.canSkip(step.kind)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.walkthrough_skip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::skipCurrentStep) {
                    Text(stringResource(R.string.walkthrough_skip))
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            SummaryCard(
                state = state,
                exhausted = state.exhausted,
                onContinue = {
                    viewModel.finish()
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun StepCard(
    kind: PermissionKind,
    fineGranted: Boolean,
    onOpenSettings: () -> Unit,
    onRequestFineLocation: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val (titleRes, bodyRes) = stepCopy(kind)
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (kind == PermissionKind.BACKGROUND_LOCATION && !fineGranted) {
                // Android 13+ posture: the runtime dialog must be the FIRST
                // ask; the "all the time" switch appears in Settings after.
                Button(onClick = { onRequestFineLocation?.invoke() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.perm_background_location_allow_fine))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.perm_background_location_allow_bg),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (kind == PermissionKind.BACKGROUND_LOCATION) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.perm_background_location_allow_bg))
                }
            } else {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (kind == PermissionKind.DEVICE_ADMIN) {
                            stringResource(R.string.perm_device_admin_activate)
                        } else {
                            stringResource(R.string.walkthrough_open_settings)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    state: WalkthroughUiState,
    exhausted: Boolean,
    onContinue: () -> Unit,
) {
    Text(
        text = stringResource(R.string.walkthrough_summary),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.walkthrough_summary_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            state.steps.forEach { step ->
                val (titleRes, _) = stepCopy(step.kind)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (step.granted) {
                            stringResource(R.string.walkthrough_done_yes)
                        } else {
                            stringResource(R.string.walkthrough_done_no)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (step.granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(
            // Exhausted with skips: the child proceeds despite off-steps; when
            // everything is granted the button is the natural finish action.
            if (exhausted) stringResource(R.string.walkthrough_continue)
            else stringResource(R.string.walkthrough_done_yes),
        )
    }
}

/** Title/body pair for the step, all child-facing copy from strings.xml. */
internal fun stepCopy(kind: PermissionKind): Pair<Int, Int> = when (kind) {
    PermissionKind.USAGE_ACCESS -> R.string.perm_usage_title to R.string.perm_usage_body
    PermissionKind.NOTIFICATIONS -> R.string.perm_notifications_title to R.string.perm_notifications_body
    PermissionKind.OVERLAY -> R.string.perm_overlay_title to R.string.perm_overlay_body
    PermissionKind.BACKGROUND_LOCATION ->
        R.string.perm_background_location_title to R.string.perm_background_location_body
    PermissionKind.DEVICE_ADMIN -> R.string.perm_device_admin_title to R.string.perm_device_admin_body
    // Parent-dashboard-only kinds never appear in the child walkthrough.
    PermissionKind.FINE_LOCATION ->
        R.string.perm_background_location_title to R.string.perm_background_location_body
    PermissionKind.BATTERY_UNRESTRICTED ->
        R.string.perm_usage_title to R.string.perm_usage_body
}
