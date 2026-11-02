package com.familyguard.feature.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.AppBlock
import com.familyguard.core.domain.model.InstalledApp
import com.familyguard.core.domain.repo.AppBlockRepository
import com.familyguard.core.domain.repo.AppInventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row after joining inventory + block state + the search query. */
data class InstalledAppRow(
    val app: InstalledApp,
    val blocked: Boolean,
)

data class InstalledAppsUiState(
    val childId: String? = null,
    val rows: List<InstalledAppRow> = emptyList(),
    /** Rows matching the current query (what the list renders). */
    val visible: List<InstalledAppRow> = emptyList(),
    val query: String = "",
    /** Live only: removed apps are hidden by default. */
    val liveCount: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val banner: String? = null,
    /** null until a refresh has reported a timestamp. */
    val lastSyncAt: String? = null,
)

/** Scalar (non-flow-joined) UI flags folded into the state. */
private data class Scalars(
    val busy: Boolean,
    val banner: String?,
    val loading: Boolean,
    val lastSyncAt: String?,
)

/**
 * M2b — App Rules inventory list (docs/M2b-PLAN.md §4).
 *
 * Read path is Room (offline-first, updated by SyncEngine/realtime); the
 * toggle writes the server first and reports failure honestly — no queued
 * write, because silently deferring a security-relevant toggle is worse than
 * telling the parent it did not apply (§3.4).
 *
 * Search is client-side over <=250 rows (no server round-trip).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InstalledAppsViewModel @Inject constructor(
    private val appInventory: AppInventoryRepository,
    private val appBlocks: AppBlockRepository,
) : ViewModel() {

    private val childIdFlow = MutableStateFlow<String?>(null)
    private val queryFlow = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(true)
    private val lastSyncAt = MutableStateFlow<String?>(null)

    fun selectChild(childId: String?) {
        childIdFlow.value = childId
    }

    fun onQueryChange(query: String) {
        queryFlow.value = query
    }

    val state: StateFlow<InstalledAppsUiState> = childIdFlow
        .flatMapLatest { childId ->
            if (childId == null) {
                flowOf(InstalledAppsUiState(loading = false))
            } else {
                combine(
                    appInventory.observe(childId),
                    appBlocks.observe(childId),
                    queryFlow,
                    combine(busy, banner, loading, lastSyncAt) { b, g, l, s ->
                        Scalars(b, g, l, s)
                    },
                ) { inventory, blocks, query, scalars ->
                    val blockedPkgs = blocks.filter { it.blocked }.map { it.packageName }.toSet()
                    val rows = inventory
                        .filter { it.removedAt == null }
                        .map { app -> InstalledAppRow(app, app.packageName in blockedPkgs) }
                        .sortedBy { it.app.appName.lowercase() }
                    InstalledAppsUiState(
                        childId = childId,
                        rows = rows,
                        visible = rows.filter { matches(it, query) },
                        query = query,
                        liveCount = rows.size,
                        loading = scalars.loading,
                        busy = scalars.busy,
                        banner = scalars.banner,
                        lastSyncAt = scalars.lastSyncAt,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InstalledAppsUiState())

    fun refresh(childId: String) = viewModelScope.launch {
        loading.value = true
        appInventory.refresh(childId)
        appBlocks.refresh(childId)
        lastSyncAt.value = java.time.Instant.now().toString()
        loading.value = false
    }

    /**
     * Parent toggle: server first, then Room. On failure the flow re-reads the
     * authoritative Room state on the next emission and we surface a banner —
     * the switch never shows a block that did not apply.
     */
    fun toggle(childId: String, packageName: String, blocked: Boolean) = viewModelScope.launch {
        busy.value = true
        banner.value = null
        when (val result = appBlocks.setBlocked(childId, packageName, blocked)) {
            is AppResult.Success -> {
                // Pull the authoritative row (server bumped `version`).
                appBlocks.refresh(childId)
            }
            is AppResult.Failure -> {
                banner.value = result.error.kind.name
                // Re-sync so the switch snaps back to server truth.
                appBlocks.refresh(childId)
            }
        }
        busy.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }

    internal companion object {
        /**
         * Case-insensitive `contains` over label + package (§4). Internal so
         * the rule is unit-tested without a ViewModel harness.
         */
        fun matches(row: InstalledAppRow, query: String): Boolean {
            if (query.isBlank()) return true
            val q = query.trim().lowercase()
            return row.app.appName.lowercase().contains(q) ||
                row.app.packageName.lowercase().contains(q)
        }
    }
}
