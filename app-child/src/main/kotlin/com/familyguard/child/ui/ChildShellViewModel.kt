package com.familyguard.child.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.data.mode.ChildDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the child shell needs to know before it can draw a route. */
enum class PairingStatus {
    /** Reading the DataStore; keep the splash up. */
    CHECKING,

    /** Paired to a child → enforcement and location may run. */
    PAIRED,

    /** Not paired yet → the join screen is the only destination. */
    NOT_PAIRED,
}

/**
 * The child app's shell state (PLAN-SPLIT §2): there is no mode flag any
 * more, only "is this install paired to a child?", which decides both the
 * start route and whether the foreground services may run.
 */
@HiltViewModel
class ChildShellViewModel @Inject constructor(
    private val childDevice: ChildDeviceRepository,
) : ViewModel() {

    private val mutable = MutableStateFlow(PairingStatus.CHECKING)
    val status: StateFlow<PairingStatus> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            childDevice.childId.collect { childId ->
                mutable.value =
                    if (childId == null) PairingStatus.NOT_PAIRED else PairingStatus.PAIRED
            }
        }
    }
}
