package com.familyguard.feature.childhub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.data.mode.ChildDeviceRepository
import com.familyguard.core.domain.enforcement.AppLimitView
import com.familyguard.core.domain.enforcement.LimitEvaluator
import com.familyguard.core.domain.enforcement.ScheduleEvaluator
import com.familyguard.core.domain.enforcement.ScheduleView
import com.familyguard.core.domain.model.AppLimit
import com.familyguard.core.domain.model.Schedule
import com.familyguard.core.domain.model.WebFilter
import com.familyguard.core.domain.model.TimeRequestStatus
import com.familyguard.core.domain.repo.RulesRepository
import com.familyguard.core.domain.repo.TimeRequestRepository
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Child-side state for the hub (PLAN C2–C5): read-only rules visibility,
 * live screen-time remaining, and the ask-for-more-time flow.
 *
 * The child device *observes* rules and usage synced down by [ChildSync];
 * it never writes rules. The only write is a `time_requests` row (C5).
 */
data class ChildUiState(
    val loading: Boolean = true,
    val childId: String? = null,
    val limits: List<AppLimit> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val webFilters: List<WebFilter> = emptyList(),
    val usedByPackage: Map<String, Long> = emptyMap(),
    /** ISO instant of the next schedule activation, for the home card. */
    val nextLockAt: Instant? = null,
    /** True only when the child's most recent request was actually refused —
     *  the request form must not nag before anything has been decided. */
    val requestDenied: Boolean = false,
    val busy: Boolean = false,
    val requestSent: Boolean = false,
    val banner: String? = null,
) {
    val todaySeconds: Long get() = usedByPackage.values.sum()

    val totalRemaining: Long
        get() = LimitEvaluator.totalRemaining(
            limits.filter { it.enabled }.map { AppLimitView(it.packageName, it.dailyLimitSeconds, it.enabled) },
            usedByPackage,
        )

    /** Screen left today, or null when no app limits are configured at all. */
    val unlimitedToday: Boolean get() = limits.none { it.enabled }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChildViewModel @Inject constructor(
    private val childDevice: ChildDeviceRepository,
    private val rulesRepository: RulesRepository,
    private val usageRepository: UsageRepository,
    private val timeRequests: TimeRequestRepository,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val requestSent = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val childId = childDevice.childIdOrNull() ?: return@launch
            // Pull the latest rules + our requests once when the hub opens;
            // the sync engine keeps the store fresh afterwards.
            rulesRepository.refresh(childId)
            timeRequests.refresh(childId)
        }
    }

    val state: StateFlow<ChildUiState> = childDevice.childId
        .flatMapLatest { childId ->
            if (childId == null) {
                flowOf(ChildUiState(loading = false))
            } else {
                combine(
                    rulesRepository.observeLimits(childId),
                    rulesRepository.observeSchedules(childId),
                    rulesRepository.observeWebFilters(childId),
                    usageRepository.observeUsage(childId),
                    // kotlinx' typed combine() tops out at 5 flows, so the
                    // request decision rides along with the local flags.
                    combine(
                        busy,
                        requestSent,
                        banner,
                        timeRequests.observeRequests(childId),
                    ) { busyNow, sent, message, requests ->
                        ChildFlags(
                            busy = busyNow,
                            sent = sent,
                            banner = message,
                            denied = requests
                                .maxByOrNull { it.createdAt }
                                ?.status == TimeRequestStatus.DENIED,
                        )
                    },
                ) { limits, schedules, filters, usage, flags ->
                    val zone = ZoneId.systemDefault()
                    val now = Instant.now()
                    ChildUiState(
                        loading = false,
                        childId = childId,
                        limits = limits,
                        schedules = schedules,
                        webFilters = filters,
                        usedByPackage = usage.associate { it.packageName to it.seconds },
                        requestDenied = flags.denied,
                        nextLockAt = schedules
                            .filter { it.enabled }
                            .map { it.toView() }
                            .mapNotNull { ScheduleEvaluator.nextActivation(it, now, zone) }
                            .minOrNull(),
                        busy = flags.busy,
                        requestSent = flags.sent,
                        banner = flags.banner,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChildUiState())

    /** C5: create a `time_requests` row the parent sees and decides on. */
    fun requestMoreTime(minutes: Int, reason: String?) = viewModelScope.launch {
        val childId = state.value.childId ?: return@launch
        val deviceId = childDevice.deviceIdOrNull() ?: return@launch
        val mins = minutes.coerceIn(1, 240)
        busy.value = true
        when (
            val result = timeRequests.requestMoreTime(
                childId = childId,
                deviceId = deviceId,
                requestedSeconds = mins * 60,
                packageName = null,
                reason = reason?.trim()?.takeIf { it.isNotEmpty() },
            )
        ) {
            is AppResult.Success -> {
                requestSent.value = true
                banner.value = null
            }
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }

    fun acknowledgeRequestSent() {
        requestSent.value = false
    }

    private fun Schedule.toView() = ScheduleView(
        name = name,
        daysMask = daysMask,
        startTime = startTime,
        endTime = endTime,
        enabled = enabled,
    )
}

/** Local flags folded together because typed combine() takes at most 5 flows. */
private data class ChildFlags(
    val busy: Boolean,
    val sent: Boolean,
    val banner: String?,
    val denied: Boolean,
)