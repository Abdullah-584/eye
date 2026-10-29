package com.familyguard.feature.rules

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.WebFilter
import com.familyguard.core.domain.model.WebFilterMode
import com.familyguard.core.domain.model.WebResolver
import com.familyguard.core.domain.repo.RulesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Row for the searchable app picker (P10). */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

data class RulesUiState(
    val childId: String? = null,
    val limits: List<AppLimit> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val webFilters: List<WebFilter> = emptyList(),
    val installedApps: List<InstalledApp> = emptyList(),
    val busy: Boolean = false,
    val banner: String? = null,
)

/** P10/P11/P12 — parent-side rule editors for one child. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rulesRepository: RulesRepository,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)
    private val childIdFlow = MutableStateFlow<String?>(null)

    val installedApps: List<InstalledApp> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { InstalledApp(it.first, it.second) }
    }

    /** The screen selects its child once; the stream follows. */
    fun selectChild(childId: String?) {
        childIdFlow.value = childId
    }

    val state: StateFlow<RulesUiState> = childIdFlow
        .flatMapLatest { childId ->
            if (childId == null) {
                flowOf(RulesUiState(installedApps = installedApps))
            } else {
                combine(
                    rulesRepository.observeLimits(childId),
                    rulesRepository.observeSchedules(childId),
                    rulesRepository.observeWebFilters(childId),
                    combine(busy, banner) { b, g -> Pair(b, g) },
                ) { limits, schedules, filters, (b, g) ->
                    RulesUiState(
                        childId = childId,
                        limits = limits,
                        schedules = schedules,
                        webFilters = filters,
                        installedApps = installedApps,
                        busy = b,
                        banner = g,
                    )
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            RulesUiState(installedApps = installedApps),
        )

    fun refresh(childId: String) = viewModelScope.launch {
        rulesRepository.refresh(childId)
    }

    fun saveLimit(childId: String, limit: AppLimit) = viewModelScope.launch {
        busy.value = true
        val result = rulesRepository.upsertLimit(limit)
        if (result is AppResult.Success) rulesRepository.refresh(childId)
        banner.value = if (result is AppResult.Success) null else (result as AppResult.Failure).error.kind.name
        busy.value = false
    }

    fun deleteLimit(childId: String, limit: AppLimit) = viewModelScope.launch {
        busy.value = true
        rulesRepository.deleteLimit(limit)
        rulesRepository.refresh(childId)
        busy.value = false
    }

    fun saveSchedule(childId: String, schedule: Schedule) = viewModelScope.launch {
        busy.value = true
        val result = rulesRepository.upsertSchedule(schedule)
        if (result is AppResult.Success) rulesRepository.refresh(childId)
        banner.value = if (result is AppResult.Success) null else (result as AppResult.Failure).error.kind.name
        busy.value = false
    }

    fun deleteSchedule(childId: String, schedule: Schedule) = viewModelScope.launch {
        busy.value = true
        rulesRepository.deleteSchedule(schedule)
        rulesRepository.refresh(childId)
        busy.value = false
    }

    fun saveWebFilter(childId: String, filter: WebFilter) = viewModelScope.launch {
        busy.value = true
        val result = rulesRepository.upsertWebFilter(filter)
        if (result is AppResult.Success) rulesRepository.refresh(childId)
        banner.value = if (result is AppResult.Success) null else (result as AppResult.Failure).error.kind.name
        busy.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }
}