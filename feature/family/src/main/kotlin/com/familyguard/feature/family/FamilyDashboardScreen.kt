package com.familyguard.feature.family

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familyguard.core.data.sync.SyncState

/** Visual scale for the screen-time ring; real per-app limits live in P10. */
private const val RING_REFERENCE_SECONDS = 8 * 3600L

/**
 * P9 — parent dashboard: child switcher, device health strip, today's screen
 * time ring, pending time-request badge, quick actions (PLAN §8.1).
 */
@Composable
fun FamilyDashboardScreen(
    state: FamilyUiState,
    onSelectChild: (String) -> Unit,
    onCreateFamilyClick: () -> Unit,
    onAddChildClick: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenDevices: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRules: (() -> Unit)? = null,
    onOpenLocation: (() -> Unit)? = null,
    onOpenReports: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = syncLabel(state.syncState),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        state.banner?.let { banner ->
            FamilyBanner(message = bannerMessage(banner), onDismiss = onDismissBanner)
            Spacer(Modifier.height(16.dp))
        }

        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.family == null -> EmptyState(
                message = stringResource(R.string.dashboard_no_family),
                actionLabel = stringResource(R.string.dashboard_create_family),
                onAction = onCreateFamilyClick,
            )

            state.children.isEmpty() -> EmptyState(
                message = stringResource(R.string.dashboard_no_children),
                actionLabel = stringResource(R.string.dashboard_add_child),
                onAction = onAddChildClick,
            )

            else -> DashboardContent(
                state = state,
                onSelectChild = onSelectChild,
                onAddChildClick = onAddChildClick,
                onOpenRequests = onOpenRequests,
                onOpenDevices = onOpenDevices,
                onOpenRules = onOpenRules,
                onOpenLocation = onOpenLocation,
                onOpenReports = onOpenReports,
            )
        }
    }
}

@Composable
private fun DashboardContent(
    state: FamilyUiState,
    onSelectChild: (String) -> Unit,
    onAddChildClick: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenRules: (() -> Unit)? = null,
    onOpenLocation: (() -> Unit)? = null,
    onOpenReports: (() -> Unit)? = null,
) {
    // Child switcher.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.children.forEach { child ->
            val selected = child.id == state.selectedChildId
            Box(
                modifier = Modifier
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSelectChild(child.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = child.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // Screen-time ring.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val fraction = (state.todaySeconds.toFloat() / RING_REFERENCE_SECONDS).coerceIn(0f, 1f)
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(132.dp),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = formatDuration(state.todaySeconds),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dashboard_screen_time_today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // Device health strip for the selected child.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.dashboard_device_health),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            val childDevices = state.devices.forChild(state.selectedChildId)
            if (childDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.dashboard_no_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                childDevices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(device.status.healthColor, CircleShape),
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = device.deviceName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = shortTimestamp(device.lastSeenAt)?.let {
                                    stringResource(R.string.dashboard_last_seen, it)
                                } ?: stringResource(R.string.dashboard_never_seen),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // Pending time-request badge.
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_requests_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (state.pendingRequests > 0) {
                        pluralStringResource(
                            R.plurals.dashboard_requests_waiting,
                            state.pendingRequests,
                            state.pendingRequests,
                        )
                    } else {
                        stringResource(R.string.dashboard_requests_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.pendingRequests > 0) {
                Button(onClick = onOpenRequests) {
                    Text(stringResource(R.string.dashboard_action_requests))
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // Quick actions.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onOpenDevices,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.dashboard_action_devices))
        }
        OutlinedButton(
            onClick = onAddChildClick,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.dashboard_add_child))
        }
    }

    // Child detail entry points (P10–P14) for the selected child.
    if (onOpenRules != null || onOpenLocation != null || onOpenReports != null) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onOpenRules?.let {
                OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_action_rules))
                }
            }
            onOpenLocation?.let {
                OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_action_location))
                }
            }
            onOpenReports?.let {
                OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_action_reports))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun syncLabel(state: SyncState): String = when (state) {
    SyncState.LIVE -> stringResource(R.string.dashboard_sync_live)
    SyncState.CONNECTING -> stringResource(R.string.dashboard_sync_connecting)
    SyncState.DEGRADED -> stringResource(R.string.dashboard_sync_degraded)
    SyncState.IDLE -> stringResource(R.string.dashboard_sync_idle)
}