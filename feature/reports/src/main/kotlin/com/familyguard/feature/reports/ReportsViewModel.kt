package com.familyguard.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.UsageDaily
import com.familyguard.core.domain.repo.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ReportsUiState(
    val childId: String? = null,
    val rangeDays: Int = 7,
    val rows: List<UsageDaily> = emptyList(),
    val busy: Boolean = false,
    val banner: String? = null,
) {
    /** seconds per local day, oldest first, for the bar chart. */
    fun dailyTotals(): List<Pair<LocalDate, Long>> {
        val byDay = rows.groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.seconds } }
        val today = LocalDate.now()
        return (rangeDays - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            day to (byDay[day.toString()] ?: 0L)
        }
    }

    fun perApp(): List<Triple<String, Long, Int>> =
        rows.groupBy { it.packageName }
            .map { (pkg, list) -> Triple(pkg, list.sumOf { it.seconds }, list.sumOf { it.pickups }) }
            .sortedByDescending { it.second }

    val totalPickups: Int get() = rows.sumOf { it.pickups }

    fun mostUsedDay(): Pair<LocalDate, Long>? = dailyTotals().maxByOrNull { it.second }
        ?.takeIf { it.second > 0 }
}

/** P14 — child detail reports tab: daily bars, per-app table, pickups, most-used day. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
) : ViewModel() {

    private val childIdFlow = MutableStateFlow<String?>(null)
    private val rangeFlow = MutableStateFlow(7)
    private val busy = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)

    fun selectChild(childId: String?) {
        childIdFlow.value = childId
    }

    val state: StateFlow<ReportsUiState> = combine(
        childIdFlow,
        rangeFlow,
        combine(busy, banner) { b, g -> Pair(b, g) },
    ) { childId, range, (b, g) -> Triple(childId, range, Pair(b, g)) }
        .flatMapLatest { (childId, range, flags) ->
            if (childId == null) {
                flowOf(ReportsUiState())
            } else {
                val today = LocalDate.now(ZoneId.systemDefault())
                val from = today.minusDays((range - 1).toLong()).toString()
                val to = today.toString()
                usageRepository.observeUsageRange(childId, from, to).map { rows ->
                    ReportsUiState(
                        childId = childId,
                        rangeDays = range,
                        rows = rows,
                        busy = flags.first,
                        banner = flags.second,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun setRange(days: Int) {
        rangeFlow.value = if (days == 30) 30 else 7
    }

    fun refresh() = viewModelScope.launch {
        val childId = childIdFlow.value ?: return@launch
        busy.value = true
        val zone = ZoneId.systemDefault()
        when (val result = usageRepository.refreshReports(childId, zone.id)) {
            is AppResult.Success -> banner.value = null
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }
}