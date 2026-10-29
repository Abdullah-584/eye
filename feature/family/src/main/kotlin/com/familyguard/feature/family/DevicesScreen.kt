package com.familyguard.feature.family

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.familyguard.core.domain.model.DeviceStatus

/**
 * P16 — paired devices: health copy per device and revocation with a confirm
 * step (revocation kills the device's refresh tokens server-side, PLAN M3).
 */
@Composable
fun DevicesScreen(
    state: FamilyUiState,
    onRevoke: (deviceId: String) -> Unit,
    onRefresh: () -> Unit,
    onDismissBanner: () -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRevokeId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.devices_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Text(stringResource(R.string.devices_refresh))
            }
        }

        Spacer(Modifier.height(16.dp))

        state.banner?.let { banner ->
            FamilyBanner(message = bannerMessage(banner), onDismiss = onDismissBanner)
            Spacer(Modifier.height(16.dp))
        }

        // Entry point to the pairing QR/code screen — without this there is no
        // way for a parent to start pairing a new child device.
        OutlinedButton(
            onClick = onPair,
            enabled = state.selectedChildId != null && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.devices_pair))
        }

        Spacer(Modifier.height(16.dp))

        if (state.devices.isEmpty()) {
            Text(
                text = stringResource(R.string.devices_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp),
            )
        }

        state.devices.forEach { device ->
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(device.status.healthColor, CircleShape),
                        )
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                        Text(
                            text = when (device.status) {
                                DeviceStatus.ACTIVE -> stringResource(R.string.device_status_active)
                                DeviceStatus.PENDING -> stringResource(R.string.device_status_pending)
                                DeviceStatus.REVOKED -> stringResource(R.string.device_status_revoked)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = device.status.healthColor,
                        )
                    }

                    val details = listOfNotNull(
                        device.model,
                        device.appVersion?.let { "v$it" },
                        shortTimestamp(device.lastSeenAt)?.let {
                            stringResource(R.string.dashboard_last_seen, it)
                        } ?: stringResource(R.string.dashboard_never_seen),
                    ).joinToString(" · ")
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    if (device.status != DeviceStatus.REVOKED) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { pendingRevokeId = device.id },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.devices_revoke))
                        }
                    }
                }
            }
        }
    }

    pendingRevokeId?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { pendingRevokeId = null },
            title = { Text(stringResource(R.string.devices_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.devices_revoke_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRevokeId = null
                        onRevoke(deviceId)
                    },
                ) {
                    Text(stringResource(R.string.devices_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevokeId = null }) {
                    Text(stringResource(R.string.devices_cancel))
                }
            },
        )
    }
}