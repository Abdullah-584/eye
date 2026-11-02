package com.familyguard.feature.requests

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyguard.core.domain.model.AdminEvent
import com.familyguard.core.domain.model.AdminEventType
import com.familyguard.core.domain.model.TimeRequest

/**
 * P15 — time requests inbox: pending queue with approve presets
 * (15/30/60 min or the requested amount) and the decided history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    childId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(childId) {
        viewModel.selectChild(childId)
        viewModel.refresh(childId)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_requests)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.banner?.let { message ->
                item {
                    Text(bannerMessage(message), color = MaterialTheme.colorScheme.error)
                }
            }

            if (state.adminAlerts.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.admin_alerts_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(state.adminAlerts, key = { "a" + it.id }) { event ->
                AdminAlertCard(
                    event = event,
                    onAck = { viewModel.acknowledgeAdminEvent(event.id) },
                )
            }

            item {
                Text(
                    stringResource(R.string.requests_pending),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.pending.isEmpty()) {
                item { Text(stringResource(R.string.requests_empty)) }
            }
            items(state.pending, key = { "p" + it.id }) { request ->
                PendingCard(
                    request = request,
                    busy = state.busy,
                    onApprove = { seconds ->
                        viewModel.decide(request.id, approve = true, grantedSeconds = seconds)
                    },
                    onDeny = { viewModel.decide(request.id, approve = false, grantedSeconds = null) },
                )
            }

            // M2c: pending uninstall requests
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.requests_uninstall_pending),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.uninstallRequests.isEmpty()) {
                item { Text(stringResource(R.string.requests_uninstall_empty)) }
            }
            items(state.uninstallRequests, key = { "u" + it.id }) { request ->
                UninstallRequestCard(
                    request = request,
                    onConfirm = { viewModel.confirmUninstall(request.id) },
                    onDecline = { viewModel.declineUninstall(request.id) },
                )
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.requests_history),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.history.isEmpty()) {
                item { Text(stringResource(R.string.requests_no_history)) }
            }
            items(state.history, key = { "h" + it.id }) { request ->
                HistoryRow(request)
            }
        }
    }
}

@Composable
private fun PendingCard(
    request: TimeRequest,
    busy: Boolean,
    onApprove: (Int) -> Unit,
    onDeny: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val label = request.packageName ?: "device"
            Text(
                stringResource(
                    R.string.requests_wants,
                    label,
                    (request.requestedSeconds / 60).coerceAtLeast(1),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            request.reason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.requests_reason, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.requests_device, request.deviceId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15, 30, 60).forEach { minutes ->
                    Button(
                        onClick = { onApprove(minutes * 60) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.requests_approve, minutes))
                    }
                }
            }
            OutlinedButton(
                onClick = onDeny,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.requests_deny))
            }
        }
    }
}

/**
 * M2a — parent alert for a device-admin tamper event. "Got it" stamps
 * parent_ack_at; the row stays in the DB as history but leaves this queue.
 */
@Composable
private fun AdminAlertCard(event: AdminEvent, onAck: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {                Text(
                    when (event.eventType) {
                        AdminEventType.DISABLED -> stringResource(
                            R.string.admin_alert_disabled, event.deviceId,
                        )
                        AdminEventType.REENABLED -> stringResource(
                            R.string.admin_alert_reenabled, event.deviceId,
                        )
                        else -> event.eventType
                    },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                event.occurredAt.take(16).replace('T', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onAck,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.admin_alert_ack))
            }
        }
    }
}

@Composable
private fun UninstallRequestCard(
    request: UninstallRequest,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                request.appName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Package: ${request.packageName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Status: ${request.status.name}",
                style = MaterialTheme.typography.bodySmall,
                color = when (request.status) {
                    UninstallRequestStatus.PENDING -> MaterialTheme.colorScheme.warning
                    UninstallRequestStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
                    UninstallRequestStatus.DECLINED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant,
                },
            )
            if (request.status == UninstallRequestStatus.PENDING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.requests_uninstall_confirm))
                    }
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.requests_uninstall_decline))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(request: TimeRequest) {
    val status = when (request.status.name) {
        "APPROVED" -> stringResource(R.string.requests_approved) +
            (request.grantedSeconds?.let { " · ${it / 60}m" } ?: "")
        "DENIED" -> stringResource(R.string.requests_denied)
        else -> stringResource(R.string.requests_expired)
    }
    ListItem(
        headlineContent = {
            Text("${request.packageName ?: "device"} · ${request.requestedSeconds / 60}m")
        },
        supportingContent = { Text(status) },
        trailingContent = { Text(request.createdAt.take(16).replace('T', ' ')) },
    )
