package com.familyguard.feature.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.common.getOrNull
import com.familyguard.core.data.sync.SyncEngine
import com.familyguard.core.data.sync.SyncState
import com.familyguard.core.domain.model.Child
import com.familyguard.core.domain.model.Device
import com.familyguard.core.domain.model.Family
import com.familyguard.core.domain.repo.FamilyRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FamilyUiState(
    val loading: Boolean = true,
    val family: Family? = null,
    val children: List<Child> = emptyList(),
    val selectedChildId: String? = null,
    val devices: List<Device> = emptyList(),
    val pendingRequests: Int = 0,
    val todaySeconds: Long = 0,
    val syncState: SyncState = SyncState.IDLE,
    val busy: Boolean = false,
    val banner: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val rulesRepository: RulesRepository,
    private val usageRepository: UsageRepository,
    private val timeRequests: TimeRequestRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    private val selectedChild = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val banner = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(true)
    private val devices = MutableStateFlow<List<Device>>(emptyList())
    private val pendingCount = MutableStateFlow(0)
    private val todaySeconds = MutableStateFlow(0L)
    private val familyFlow = MutableStateFlow<Family?>(null)

    private var syncedChildIds: List<String> = emptyList()

    init {
        viewModelScope.launch {
            familyFlow.value = familyRepository.getFamily().getOrNull()
            familyRepository.refreshChildren()
            devices.value = familyRepository.refreshDevices().getOrNull() ?: emptyList()
            familyRepository.refreshAudit()
            loading.value = false
        }
    }

    val state: StateFlow<FamilyUiState> = combine(
        familyRepository.observeChildren(),
        selectedChild,
        combine(busy, banner, loading) { b, g, l -> Triple(b, g, l) },
        combine(devices, pendingCount, todaySeconds, syncEngine.state) { d, p, t, s -> Quadruple(d, p, t, s) },
    ) { children, selected, (b, g, l), (d, p, t, s) ->
        // Keep parent realtime in step with the child list, but never churn
        // the channel: only restart when membership actually changes.
        val ids = children.map { it.id }
        if (ids != syncedChildIds) {
            syncedChildIds = ids
            syncEngine.startParentSync(ids)
        }
        val sel = selected ?: children.firstOrNull()?.id
        FamilyUiState(
            loading = l,
            family = familyFlow.value,
            children = children,
            selectedChildId = sel,
            devices = d,
            pendingRequests = p,
            todaySeconds = t,
            syncState = s,
            busy = b,
            banner = g,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyUiState())

    init {
        viewModelScope.launch {
            familyRepository.observeChildren().collect { children ->
                children.firstOrNull()?.let { ensureSelected(it.id) }
            }
        }
    }

    fun selectChild(childId: String) {
        selectedChild.value = childId
        ensureSelected(childId)
    }

    private fun ensureSelected(childId: String) {
        viewModelScope.launch {
            timeRequests.refresh(childId)
            usageRepository.refreshReports(childId, "UTC")
            timeRequests.observeRequests(childId).collect { list ->
                pendingCount.value = list.count { it.status.name == "PENDING" }
            }
        }
        viewModelScope.launch {
            usageRepository.observeUsage(childId).collect { rows ->
                todaySeconds.value = rows.sumOf { it.seconds }
            }
        }
    }

    fun createFamily(
        name: String,
        consentPolicyVersion: String,
        onResult: (Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        busy.value = true
        when (val result = familyRepository.createFamily(name, consentPolicyVersion)) {
            is AppResult.Success -> {
                familyFlow.value = result.value
                familyRepository.refreshChildren()
                banner.value = null
                onResult(true)
            }
            is AppResult.Failure -> {
                banner.value = result.error.kind.name
                onResult(false)
            }
        }
        busy.value = false
    }

    fun addChild(
        name: String,
        birthYear: Int?,
        timezone: String,
        onResult: (Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        busy.value = true
        when (val result = familyRepository.addChild(name, birthYear, timezone)) {
            is AppResult.Success -> {
                familyRepository.refreshChildren()
                banner.value = null
                onResult(true)
            }
            is AppResult.Failure -> {
                banner.value = result.error.kind.name
                onResult(false)
            }
        }
        busy.value = false
    }

    /**
     * Re-reads the family from cache+server. Called whenever the dashboard
     * comes back on screen so a family created on another screen (or another
     * ViewModel instance) shows up without restarting the app.
     */
    fun refreshFamily() = viewModelScope.launch {
        familyFlow.value = familyRepository.getFamily().getOrNull()
    }

    fun revokeDevice(deviceId: String) = viewModelScope.launch {
        busy.value = true
        when (val result = familyRepository.revokeDevice(deviceId)) {
            is AppResult.Success -> devices.value = familyRepository.refreshDevices().getOrNull() ?: devices.value
            is AppResult.Failure -> banner.value = result.error.kind.name
        }
        busy.value = false
    }

    fun refreshDevices() = viewModelScope.launch {
        devices.value = familyRepository.refreshDevices().getOrNull() ?: emptyList()
    }

    fun dismissBanner() {
        banner.value = null
    }

    override fun onCleared() {
        // Deliberately NOT syncEngine.stop(): SyncEngine is a process-wide
        // singleton and the next dashboard's ViewModel would otherwise find
        // its own start() already torn down by this stale stop(). The engine
        // closes the channel itself when the child list becomes empty.
        super.onCleared()
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
