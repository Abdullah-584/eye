package com.familyguard.feature.reports

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.TextStyle
import java.util.Locale

/**
 * P14 — child detail reports: 7/30-day range, daily bar chart, per-app
 * breakdown table, pickups and the most-used day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    childId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(childId) {
        viewModel.selectChild(childId)
        viewModel.refresh()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_reports)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.reports_refresh))
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.banner?.let { message ->
                item {
                    Text(bannerMessage(message), color = MaterialTheme.colorScheme.error)
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.rangeDays == 7,
                        onClick = { viewModel.setRange(7) },
                        label = { Text(stringResource(R.string.reports_range_7)) },
                    )
                    FilterChip(
                        selected = state.rangeDays == 30,
                        onClick = { viewModel.setRange(30) },
                        label = { Text(stringResource(R.string.reports_range_30)) },
                    )
                }
            }

            if (state.rows.isEmpty()) {
                item { Text(stringResource(R.string.reports_empty)) }
                return@LazyColumn
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.reports_daily),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val totals = state.dailyTotals()
                        val maxSeconds = totals.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
                        DailyBarChart(
                            totals = totals,
                            maxSeconds = maxSeconds,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .padding(top = 12.dp),
                        )
                        val totalSeconds = totals.sumOf { it.second }
                        Text(
                            stringResource(R.string.reports_total, formatHM(totalSeconds)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        val mostUsed = state.mostUsedDay()
                        if (mostUsed != null) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.reports_most_used)) },
                                supportingContent = {
                                    Text(
                                        "${mostUsed.first.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} · " +
                                            formatHM(mostUsed.second),
                                    )
                                },
                            )
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.reports_pickups)) },
                            supportingContent = { Text(state.totalPickups.toString()) },
                        )
                    }
                }
            }

            item {
                Text(stringResource(R.string.reports_per_app), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.reports_table_app), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.reports_table_time), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.reports_table_pickups), style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
            }
            items(state.perApp(), key = { it.first }) { (pkg, seconds, pickups) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        pkg.substringAfterLast('.'),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(formatHM(seconds), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(pickups.toString(), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DailyBarChart(
    totals: List<Pair<java.time.LocalDate, Long>>,
    maxSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val barCount = totals.size
        val slot = size.width / barCount
        val barWidth = slot * 0.6f

        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)

        totals.forEachIndexed { index, (_, seconds) ->
            val fraction = if (maxSeconds <= 0L) 0f else seconds.toFloat() / maxSeconds
            val barHeight = (fraction * size.height).coerceAtLeast(if (seconds > 0) 3f else 0f)
            val left = index * slot + (slot - barWidth) / 2f
            drawRect(
                color = barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

private fun formatHM(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}