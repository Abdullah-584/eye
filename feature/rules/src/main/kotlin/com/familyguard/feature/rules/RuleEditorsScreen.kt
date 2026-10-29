package com.familyguard.feature.rules

import com.familyguard.core.ui.bannerMessage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.WebFilter
import com.familyguard.core.domain.model.WebFilterMode
import com.familyguard.core.domain.model.WebResolver
import java.util.UUID

/**
 * Child detail — Apps / Schedule / Web tabs (PLAN P10, P11, P12).
 * Every write goes through RulesRepository → Postgrest (RLS) and lands back
 * in Room, so the tab updates live for the child within seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorsScreen(
    childId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(childId) {
        viewModel.selectChild(childId)
        viewModel.refresh(childId)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_rules)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_apps)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_schedule)) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_web)) })
            }

            state.banner?.let { message ->
                Text(
                    bannerMessage(message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            when (tab) {
                0 -> AppsTab(state, viewModel, childId)
                1 -> ScheduleTab(state, viewModel, childId)
                else -> WebTab(state, viewModel, childId)
            }
        }
    }
}

/* ----------------------------- P10: apps ----------------------------- */

@Composable
private fun AppsTab(state: RulesUiState, viewModel: RulesViewModel, childId: String) {
    var editing by remember { mutableStateOf<AppLimit?>(null) }
    var picking by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.limits.isEmpty()) {
            item { Text(stringResource(R.string.apps_empty)) }
        }
        items(state.limits, key = { it.id }) { limit ->
            val label = state.installedApps.firstOrNull { it.packageName == limit.packageName }?.label
                ?: limit.packageName
            Card(Modifier.fillMaxWidth().clickable { editing = limit }) {
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = {
                        Text(
                            if (!limit.enabled) "—"
                            else if (limit.dailyLimitSeconds <= 0) stringResource(R.string.apps_block_entirely)
                            else formatLimit(limit.dailyLimitSeconds),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = limit.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.saveLimit(childId, limit.copy(enabled = enabled))
                            },
                        )
                    },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apps_add))
            }
        }
    }

    editing?.let { limit ->
        LimitDialog(
            limit = limit,
            state = state,
            onDismiss = { editing = null },
            onSave = { updated ->
                viewModel.saveLimit(childId, updated)
                editing = null
            },
            onDelete = {
                viewModel.deleteLimit(childId, limit)
                editing = null
            },
        )
    }

    if (picking) {
        AppPickerDialog(
            apps = state.installedApps,
            existing = state.limits.map { it.packageName }.toSet(),
            onDismiss = { picking = false },
            onPick = { pkg ->
                picking = false
                editing = AppLimit(
                    id = UUID.randomUUID().toString(),
                    childId = childId,
                    packageName = pkg,
                    dailyLimitSeconds = 3600,
                )
            },
        )
    }
}

@Composable
private fun LimitDialog(
    limit: AppLimit,
    state: RulesUiState,
    onDismiss: () -> Unit,
    onSave: (AppLimit) -> Unit,
    onDelete: () -> Unit,
) {
    var minutes by remember(limit.id) {
        mutableStateOf((limit.dailyLimitSeconds / 60).coerceIn(0, 720).toFloat().toString())
    }
    var blockAll by remember(limit.id) { mutableStateOf(limit.dailyLimitSeconds <= 0) }
    var enabled by remember(limit.id) { mutableStateOf(limit.enabled) }
    val label = state.installedApps.firstOrNull { it.packageName == limit.packageName }?.label
        ?: limit.packageName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!blockAll) {
                    Text(stringResource(R.string.apps_daily_limit), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = (minutes.toFloatOrNull() ?: 60f).coerceIn(0f, 720f),
                        onValueChange = { minutes = it.toInt().toString() },
                        valueRange = 0f..720f,
                        steps = 31,
                    )
                    Text(formatLimit((minutes.toFloatOrNull() ?: 0f).toInt() * 60))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.apps_block_entirely), Modifier.weight(1f))
                    Switch(checked = blockAll, onCheckedChange = { blockAll = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.apps_enabled), Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val totalSeconds = if (blockAll) 0 else (minutes.toIntOrNull() ?: 60) * 60
                    onSave(limit.copy(dailyLimitSeconds = totalSeconds, enabled = enabled))
                },
            ) { Text(stringResource(R.string.schedule_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.apps_delete)) }
                TextButton(onClick = onDismiss) { Text("—") }
            }
        },
    )
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter {
        it.packageName !in existing &&
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.apps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.height(320.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName) },
                            modifier = Modifier.clickable { onPick(app.packageName) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

private fun formatLimit(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/* --------------------------- P11: schedule --------------------------- */

@Composable
private fun ScheduleTab(state: RulesUiState, viewModel: RulesViewModel, childId: String) {
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.schedules.isEmpty()) {
            item { Text(stringResource(R.string.schedule_empty)) }
        }
        items(state.schedules, key = { it.id }) { schedule ->
            Card(Modifier.fillMaxWidth().clickable { editing = schedule }) {
                ListItem(
                    headlineContent = { Text(schedule.name) },
                    supportingContent = {
                        Text("${dayLabels(schedule.daysMask)} · ${schedule.startTime}–${schedule.endTime}")
                    },
                    trailingContent = {
                        Switch(
                            checked = schedule.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.saveSchedule(childId, schedule.copy(enabled = enabled))
                            },
                        )
                    },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    creating = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.schedule_add)) }
        }
    }

    val toEdit = editing ?: if (creating) Schedule(
        id = UUID.randomUUID().toString(),
        childId = childId,
        name = "",
        startTime = "21:00",
        endTime = "07:00",
    ) else null

    toEdit?.let { schedule ->
        ScheduleDialog(
            schedule = schedule,
            onDismiss = {
                editing = null
                creating = false
            },
            onSave = { updated ->
                viewModel.saveSchedule(childId, updated)
                editing = null
                creating = false
            },
            onDelete = {
                viewModel.deleteSchedule(childId, schedule)
                editing = null
                creating = false
            },
        )
    }
}

@Composable
private fun ScheduleDialog(
    schedule: Schedule,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(schedule.id) { mutableStateOf(schedule.name) }
    var start by remember(schedule.id) { mutableStateOf(schedule.startTime) }
    var end by remember(schedule.id) { mutableStateOf(schedule.endTime) }
    var days by remember(schedule.id) { mutableIntStateOf(schedule.daysMask) }
    var enabled by remember(schedule.id) { mutableStateOf(schedule.enabled) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.schedule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it.take(5) },
                        label = { Text(stringResource(R.string.schedule_start)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it.take(5) },
                        label = { Text(stringResource(R.string.schedule_end)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (error) {
                    Text(
                        stringResource(R.string.schedule_bad_time),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.schedule_days), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayShortNames().forEachIndexed { index, label ->
                        val bit = if (index == 0) 0 else index // order Sun..Sat
                        FilterChip(
                            selected = ((days shr bit) and 1) == 1,
                            onClick = { days = days xor (1 shl bit) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.schedule_enabled), Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val time = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
                    if (!time.matches(start) || !time.matches(end) || name.isBlank()) {
                        error = true
                    } else {
                        onSave(
                            schedule.copy(
                                name = name.trim(),
                                startTime = start,
                                endTime = end,
                                daysMask = days,
                                enabled = enabled,
                            ),
                        )
                    }
                },
            ) { Text(stringResource(R.string.schedule_save)) }
        },
        dismissButton = {
            Row {
                if (schedule.name.isNotBlank()) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.schedule_delete)) }
                }
                TextButton(onClick = onDismiss) { Text("—") }
            }
        },
    )
}

private fun dayShortNames() = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

private fun dayLabels(mask: Int): String {
    val names = dayShortNames()
    val picked = (0..6).filter { ((mask shr it) and 1) == 1 }.map { names[it] }
    return if (picked.size == 7) "Mon–Sun" else picked.joinToString(" ")
}

/* ------------------------------ P12: web ----------------------------- */

private val CATEGORY_OPTIONS = listOf("Social", "Games", "Video", "Shopping", "News", "Gambling")

@Composable
private fun WebTab(state: RulesUiState, viewModel: RulesViewModel, childId: String) {
    val existing = state.webFilters.firstOrNull()
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: WebFilterMode.BLOCKLIST) }
    var categories by remember(existing?.id) { mutableStateOf(existing?.categories.orEmpty()) }
    var domains by remember(existing?.id) { mutableStateOf(existing?.domains.orEmpty()) }
    var resolver by remember(existing?.id) { mutableStateOf(existing?.resolver ?: WebResolver.SAFE_DNS) }
    var newDomain by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.web_mode), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == WebFilterMode.BLOCKLIST,
                onClick = { mode = WebFilterMode.BLOCKLIST },
                label = { Text(stringResource(R.string.web_blocklist)) },
            )
            FilterChip(
                selected = mode == WebFilterMode.ALLOWLIST,
                onClick = { mode = WebFilterMode.ALLOWLIST },
                label = { Text(stringResource(R.string.web_allowlist)) },
            )
        }

        Text(stringResource(R.string.web_categories), style = MaterialTheme.typography.titleSmall)
        // Chips overflow sideways, so this row scrolls horizontally. A
        // verticalScroll here would sit inside the tab's verticalScroll
        // Column and Compose crashes with infinite-height constraints.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            CATEGORY_OPTIONS.forEach { category ->
                FilterChip(
                    selected = category in categories,
                    onClick = {
                        categories = if (category in categories) categories - category else categories + category
                    },
                    label = { Text(category) },
                )
            }
        }

        Text(stringResource(R.string.web_domains), style = MaterialTheme.typography.titleSmall)
        domains.forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(domain, Modifier.weight(1f))
                TextButton(onClick = { domains = domains - domain }) { Text("✕") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newDomain,
                onValueChange = { newDomain = it },
                label = { Text(stringResource(R.string.web_add_domain)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val cleaned = newDomain.trim().removePrefix("https://").removePrefix("http://")
                    if (cleaned.isNotBlank() && cleaned !in domains) {
                        domains = domains + cleaned
                        newDomain = ""
                    }
                },
            ) { Text("+") }
        }
        Text(
            stringResource(R.string.web_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.web_resolver), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = resolver == WebResolver.SAFE_DNS,
                onClick = { resolver = WebResolver.SAFE_DNS },
                label = { Text(stringResource(R.string.web_resolver_dns)) },
            )
            FilterChip(
                selected = resolver == WebResolver.LOCAL_BLOCKLIST,
                onClick = { resolver = WebResolver.LOCAL_BLOCKLIST },
                label = { Text(stringResource(R.string.web_resolver_local)) },
            )
        }

        if (saved) {
            Text(
                stringResource(R.string.web_saved),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Button(
            onClick = {
                val filter = WebFilter(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    childId = childId,
                    mode = mode,
                    domains = domains,
                    categories = categories,
                    resolver = resolver,
                    enabled = true,
                    version = (existing?.version ?: 0) + 1,
                )
                viewModel.saveWebFilter(childId, filter)
                saved = true
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.web_save)) }

        HorizontalDivider()
    }
}
