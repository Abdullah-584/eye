package com.familyguard.feature.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyguard.core.ui.bannerMessage

/**
 * M2b — App Rules inventory list (docs/M2b-PLAN.md §4): monogram, label,
 * package name, block switch, system badge, search and a last-sync line.
 *
 * No uninstall affordance (that is M2c) and no disabled placeholder — the
 * scope stays clean. Accessibility: 48 dp switch target via default sizing,
 * the switch state is in its contentDescription (never colour-only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    childId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InstalledAppsViewModel = hiltViewModel(),
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
                title = { Text(stringResource(R.string.apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
            )

            state.banner?.let { message ->
                Text(
                    text = bannerMessage(message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            when {
                state.loading && state.rows.isEmpty() -> LoadingState()

                state.rows.isEmpty() -> EmptyState(
                    onRetry = { viewModel.refresh(childId) },
                )

                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.apps_last_sync, syncLabel(state.lastSyncAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.apps_count, state.liveCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.visible, key = { it.app.deviceId + "|" + it.app.packageName }) { row ->
                            AppRow(
                                row = row,
                                busy = state.busy,
                                onToggle = { blocked ->
                                    viewModel.toggle(childId, row.app.packageName, blocked)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    row: InstalledAppRow,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(row.app.appName, row.app.packageName)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.app.appName.ifBlank { row.app.packageName },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (row.app.isSystem) {
                    SystemBadge()
                }
            }
            Text(
                text = row.app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val description = stringResource(
            R.string.apps_switch_desc,
            row.app.appName.ifBlank { row.app.packageName },
            if (row.blocked) {
                stringResource(R.string.apps_state_on)
            } else {
                stringResource(R.string.apps_state_off)
            },
        )
        Switch(
            checked = row.blocked,
            enabled = !busy,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** Deterministic monogram: first grapheme of the label, colour from the package hash. */
@Composable
private fun Monogram(label: String, packageName: String) {
    val initial = label.trim().firstOrNull()?.uppercaseChar() ?: '?'
    val hue = (packageName.hashCode() % 360 + 360) % 360
    val color = Color.hsl(hue.toFloat(), 0.55f, 0.45f)
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun SystemBadge() {
    Text(
        text = stringResource(R.string.apps_system),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.apps_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.apps_retry))
        }
    }
}

private fun syncLabel(lastSyncAt: String?): String =
    lastSyncAt?.take(16)?.replace('T', ' ') ?: "—"
