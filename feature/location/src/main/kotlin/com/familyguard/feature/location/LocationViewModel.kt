package com.familyguard.feature.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.LocationFix
import com.familyguard.core.domain.repo.LocationRepository
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

data class LocationUiState(
    val childId: String? = null,
    val fix: LocationFix? = null,
    val refreshing: Boolean = false,
    val banner: String? = null,
)

/** P13 — parent side: last reported fix with the "located X ago" honesty label. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
) : ViewModel() {

    private val childIdFlow = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)

    fun selectChild(childId: String?) {
        childIdFlow.value = childId
    }

    val state: StateFlow<LocationUiState> = childIdFlow
        .flatMapLatest { childId ->
            if (childId == null) {
                flowOf(LocationUiState())
            } else {
                combine(
                    locationRepository.observeLatest(childId),
                    combine(refreshing, banner) { r, g -> Pair(r, g) },
                ) { fix, (r, g) ->
                    LocationUiState(childId = childId, fix = fix, refreshing = r, banner = g)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationUiState())

    fun refresh() = viewModelScope.launch {
        val childId = childIdFlow.value ?: return@launch
        refreshing.value = true
        when (val result = locationRepository.refreshLatest(childId)) {
            is AppResult.Success -> banner.value = null
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        refreshing.value = false
    }

    fun dismissBanner() {
        banner.value = null
    }
}