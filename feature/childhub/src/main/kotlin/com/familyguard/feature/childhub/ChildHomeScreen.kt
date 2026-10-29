package com.familyguard.feature.childhub

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * C2 — child home: a single "Protection is ON" card with the live rule
 * summary (screen time left today, next bedtime, blocked-site count) plus
 * entry points to the rule viewer, the privacy transparency screen and the
 * ask-for-more-time flow (C3–C5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildHomeScreen(
    state: ChildUiState,
    onRequestTime: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.title_child_home)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.banner?.let { ChildBanner(bannerMessage(it), onDismissBanner) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.protection_on),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        stringResource(R.string.protection_on_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SummaryCell(
                            label = stringResource(R.string.home_screen_left),
                            value = if (state.unlimitedToday) {
                                stringResource(R.string.home_unlimited)
                            } else {
                                formatDuration(state.totalRemaining)
                            },
                        )
                        SummaryCell(
                            label = stringResource(R.string.home_bedtime),
                            value = formatInstantTime(state.nextLockAt)
                                ?: stringResource(R.string.home_no_bedtime),
                        )
                        SummaryCell(
                            label = stringResource(R.string.home_blocked_sites),
                            value = state.webFilters.sumOf { it.domains.size }.toString(),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onRequestTime,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.request_title))
            }

            NavRow(
                icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                title = stringResource(R.string.title_child_rules),
                subtitle = stringResource(R.string.rules_intro),
                onClick = onOpenRules,
            )
            NavRow(
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                title = stringResource(R.string.title_child_privacy),
                subtitle = stringResource(R.string.privacy_intro),
                onClick = onOpenPrivacy,
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun NavRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}