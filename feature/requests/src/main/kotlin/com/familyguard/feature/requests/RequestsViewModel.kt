package com.familyguard.feature.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.TimeRequest
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
    val busy: Boolean = false,
    val banner: String? = null,
)

/** P15 — parent inbox: approve/deny pending requests (with 15/30/60 presets) + history. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RequestsViewModel @Inject constructor(
    private val timeRequests: TimeRequestRepository,
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
                    combine(busy, banner) { b, g -> Pair(b, g) },
                ) { requests, (b, g) ->
                    RequestsUiState(
                        childId = childId,
                        pending = requests.filter { it.status.name == "PENDING" },
                        history = requests.filter { it.status.name != "PENDING" },
                        busy = b,
                        banner = g,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestsUiState())

    fun refresh(childId: String) = viewModelScope.launch {
        timeRequests.refresh(childId)
    }

    fun decide(requestId: String, approve: Boolean, grantedSeconds: Int?) = viewModelScope.launch {
        busy.value = true
        when (val result = timeRequests.decide(requestId, approve, grantedSeconds)) {
            is AppResult.Success -> {
                banner.value = null
                result.value.childId.let { timeRequests.refresh(it) }
            }
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }
}