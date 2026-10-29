package com.familyguard.feature.childhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * C3 — the child can always *see* every rule that applies to them:
 * per-app limits, schedules and the web filter, read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildRulesScreen(
    state: ChildUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_child_rules)) },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.limits.isEmpty() && state.schedules.isEmpty() && state.webFilters.isEmpty()) {
                Text(stringResource(R.string.rules_empty))
                return@Column
            }

            if (state.limits.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.rules_section_apps)) {
                    state.limits.forEach { limit ->
                        ListItem(
                            headlineContent = { Text(prettyAppName(limit.packageName)) },
                            supportingContent = { Text(limit.packageName) },
                            trailingContent = {
                                Text(
                                    if (limit.enabled) formatDuration(limit.dailyLimitSeconds.toLong())
                                    else stringResource(R.string.home_unlimited),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            },
                        )
                    }
                }
            }

            if (state.schedules.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.rules_section_schedule)) {
                    state.schedules.forEach { schedule ->
                        ListItem(
                            headlineContent = { Text(schedule.name) },
                            supportingContent = {
                                Text(
                                    "${formatDays(schedule.daysMask)} · " +
                                        "${schedule.startTime}–${schedule.endTime}",
                                )
                            },
                        )
                    }
                }
            }

            state.webFilters.forEach { filter ->
                SectionCard(
                    title = stringResource(
                        if (filter.mode == com.familyguard.core.domain.model.WebFilterMode.ALLOWLIST) {
                            R.string.rules_allowlist_mode
                        } else {
                            R.string.rules_blocklist_mode
                        },
                    ),
                ) {
                    // The blurb must describe the mode actually configured:
                    // blocklist and allowlist read the opposite way around.
                    Text(
                        stringResource(
                            if (filter.mode == com.familyguard.core.domain.model.WebFilterMode.ALLOWLIST) {
                                R.string.rules_allowlist_info
                            } else {
                                R.string.rules_blocklist_info
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    if (filter.categories.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.rules_categories,
                                filter.categories.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                    filter.domains.forEach { domain ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(domain, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(8.dp))
                            SuggestionChip(
                                onClick = {},
                                enabled = false,
                                label = {
                                    Text(
                                        stringResource(
                                            if (filter.mode == com.familyguard.core.domain.model.WebFilterMode.ALLOWLIST) {
                                                R.string.rules_allow
                                            } else {
                                                R.string.rules_block
                                            },
                                        ),
                                    )
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
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            content()
        }
    }
}