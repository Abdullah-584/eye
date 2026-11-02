package com.familyguard.feature.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.AdminEvent
import com.familyguard.core.domain.model.AdminEventType
import com.familyguard.core.domain.model.TimeRequest
import com.familyguard.core.domain.model.UninstallRequest
import com.familyguard.core.domain.model.UninstallRequestStatus
import com.familyguard.core.domain.repo.AdminEventRepository
import com.familyguard.core.domain.repo.UninstallRequestRepository
import com.familyguard.core.domain.repo.TimeRequestRepository
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

data class RequestsUiState(
    val childId: String? = null,
    val pending: List<TimeRequest> = emptyList(),
    val history: List<TimeRequest> = emptyList(),
    /** M2a: unacknowledged device-admin alerts (newest first). */
    val adminAlerts: List<AdminEvent> = emptyList(),
    val busy: Boolean = false,
    val banner: String? = null,
    /** M2c: pending uninstall requests for this child. */
    val uninstallRequests: List<UninstallRequest> = emptyList(),
)

/** P15 — parent inbox: approve/deny pending requests (with 15/30/60 presets) + history. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val timeRequests: TimeRequestRepository,
    private val adminEvents: AdminEventRepository,
    private val uninstallRequestRepo: UninstallRequestRepository,
) : ViewModel() {

    private val childIdFlow = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)

    fun selectChild(childId: String?) {
        childIdFlow.value = childId
    }

    val state: StateFlow<RequestsUiState> = childIdFlow
        .flatMapLatest { childId ->
            if (childId == null) {
                flowOf(RequestsUiState())
            } else {
                combine(
                    timeRequests.observeRequests(childId),
                    adminEvents.observe(childId),
                    uninstallRequestRepo.observeRequests(childId).map { it.map { it.toDomain() } },
                    combine(busy, banner) { b, g -> Pair(b, g) },
                ) { requests, admin, uninstall, (b, g) ->
                    RequestsUiState(
                        childId = childId,
                        pending = requests.filter { it.status.name == "PENDING" },
                        history = requests.filter { it.status.name != "PENDING" },
                        adminAlerts = admin.filter { it.parentAckAt == null },
                        busy = b,
                        banner = g,
                        uninstallRequests = uninstall,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestsUiState())

    fun refresh(childId: String) = viewModelScope.launch {
        timeRequests.refresh(childId)
        // Realtime is the fast path; this poll keeps admin alerts honest when
        // the socket was down (M2-PLAN §2.4, same cadence as time requests).
        adminEvents.refresh(childId)
        uninstallRequestRepo.refresh(childId)
    }

    /** "Got it": stamps parent_ack_at; the row remains as history. */
    fun acknowledgeAdminEvent(eventId: String) {
        banner.value = null
        viewModelScope.launch {
            when (val result = adminEvents.acknowledge(eventId)) {
                is AppResult.Success -> state.value.childId?.let { adminEvents.refresh(it) }
                is AppResult.Failure -> banner.value = result.error.kind.name
            }
        }
    }

    fun decide(requestId: String, approve: Boolean, grantedSeconds: Int?) = viewModelScope.launch {
        busy.value = true
        when (val result = timeRequests.decide(requestId, approve, grantedSeconds)) {
            is AppResult.Success -> {
                banner.value = null
                result.value.childId?.let { timeRequests.refresh(it) }
            }
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }

    /** M2c: parent initiates an uninstall request for a non-system app. */
    fun requestUninstall(childId: String, packageName: String, appName: String, requestedBy: String) = viewModelScope.launch {
        busy.value = true
        val result = uninstallRequestRepo.upsertUninstallRequest(childId, packageName, appName, requestedBy)
        when (result) {
            is AppResult.Success -> {
                // Request written; realtime will surface it to the child.
                banner.value = null
            }
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    /** M2c: child confirms an uninstall request. */
    fun confirmUninstall(requestId: String) = viewModelScope.launch {
        busy.value = true
        val result = uninstallRequestRepo.decide(requestId, true)
        when (result) {
            is AppResult.Success -> {
                // Request confirmed; child will launch system dialog.
                banner.value = null
            }
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    /** M2c: child declines an uninstall request. */
    fun declineUninstall(requestId: String) = viewModelScope.launch {
        busy.value = true
        val result = uninstallRequestRepo.decide(requestId, false)
        when (result) {
            is AppResult.Success -> {
                // Request declined.
                banner.value = null
            }
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    fun dismissBanner2() {
        banner.value = null
    }
}