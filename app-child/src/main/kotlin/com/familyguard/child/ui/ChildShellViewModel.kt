package com.familyguard.child.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.data.mode.ChildDeviceRepository
import com.familyguard.core.permissions.PermissionChecker
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
    private val permissionChecker: PermissionChecker,
) : ViewModel() {

    private val mutable = MutableStateFlow(PairingStatus.CHECKING)
    val status: StateFlow<PairingStatus> = mutable.asStateFlow()

    /** M2a: paired but the permission walkthrough is required. */
    private val mutablePermsPending = MutableStateFlow(false)
    val permsPending: StateFlow<Boolean> = mutablePermsPending.asStateFlow()

    init {
        viewModelScope.launch {
            childDevice.childId.collect { childId ->
                mutable.value =
                    if (childId == null) PairingStatus.NOT_PAIRED else PairingStatus.PAIRED
                // Re-evaluate the walkthrough gate at every cold start (the
                // shell is created once per process: M2-PLAN §1.1 — reopen
                // while a required permission is missing; deferral (walking
                // through a skip) never re-routes mid-session, so the child
                // is not locked into the corner mid-use.
                evaluatePermsGate()
            }
        }
    }

    fun evaluatePermsGate() {
        if (mutable.value != PairingStatus.PAIRED) {
            mutablePermsPending.value = false
            return
        }
        // New installs walk through even with everything granted once (the
        // summary is where the child learns what monitoring means); installs
        // that already walked it reopen ONLY when something is missing —
        // M2-PLAN §1.1's "re-shown if a required permission was revoked".
        viewModelScope.launch {
            val seen = childDevice.permWalkthroughSeenOrNull() == true
            val anyMissing = PermissionChecker.CHILD_STEPS.any { !permissionChecker.check(it).granted }
            mutablePermsPending.value = !seen || anyMissing
        }
    }
}
