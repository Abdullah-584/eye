package com.familyguard.feature.childhub

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.data.mode.ChildDeviceRepository
import com.familyguard.core.permissions.PermissionChecker
import com.familyguard.core.permissions.PermissionKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalkthroughStepUi(
    val kind: PermissionKind,
    val granted: Boolean,
    val skipped: Boolean,
)

data class WalkthroughUiState(
    val steps: List<WalkthroughStepUi> = emptyList(),
    /** Index of the step on screen; == steps.size → the summary screen. */
    val activeIndex: Int = 0,
    /** True when the child reached the summary with something skipped. */
    val exhausted: Boolean = false,
    /** The background-location step shows the runtime ask first while unset. */
    val fineGranted: Boolean = false,
)

/**
 * M2a permission walkthrough (M2-PLAN §1): one screen per permission, in the
 * plan's order ([PermissionChecker.CHILD_STEPS]).
 *
 * Grant-state truth: [recheck] queries the *system* for every step — a tap on
 * "Next" never advances by itself. The screen calls [recheck] on every
 * ON_RESUME, which covers the two ways a grant can actually happen: the
 * runtime dialog result, and coming back from a system Settings screen.
 *
 * Device admin cannot be skipped; everything else can be deferred with a
 * visible "(this stays off)" — and the walkthrough re-opens on the next cold
 * start while a step is ungranted, so skipping is a deferral, not an escape.
 */
@HiltViewModel
class WalkthroughViewModel @Inject constructor(
    private val checker: PermissionChecker,
    private val childDevice: ChildDeviceRepository,
) : ViewModel() {

    private val stepKinds: List<PermissionKind> = PermissionChecker.CHILD_STEPS

    private val granted = MutableStateFlow(stepKinds.map { false })
    private val skipped = MutableStateFlow(stepKinds.map { false })

    private val fineGranted = MutableStateFlow(false)

    val state: StateFlow<WalkthroughUiState> =
        combine(granted, skipped, fineGranted) { g, s, fine ->
            WalkthroughUiState(
                steps = stepKinds.mapIndexed { i, kind ->
                    WalkthroughStepUi(kind = kind, granted = g[i], skipped = s[i])
                },
                activeIndex = WalkthroughFlow.activeIndex(g, s),
                exhausted = WalkthroughFlow.exhaustedWithSkips(g, s),
                fineGranted = fine,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalkthroughUiState())

    init {
        recheck()
    }

    /** Query the REAL system for each step. This is the only way forward. */
    fun recheck() {
        granted.value = stepKinds.map { checker.check(it).granted }
        fineGranted.value = checker.check(PermissionKind.FINE_LOCATION).granted
        // Everything granted on a recheck: the walkthrough is genuinely done —
        // persist it so the hub unlocks without a manual "Continue".
        if (granted.value.all { it }) {
            viewModelScope.launch { childDevice.markPermWalkthroughSeen() }
        }
    }

    /** Skips only what [WalkthroughFlow.canSkip] allows (never admin). */
    fun skipCurrentStep() {
        val g = granted.value
        val s = skipped.value
        val index = WalkthroughFlow.activeIndex(g, s)
        val kind = stepKinds.getOrNull(index) ?: return
        if (!WalkthroughFlow.canSkip(kind)) return
        skipped.value = s.toMutableList().also { it[index] = true }
    }

    /** Summary "Continue": records the walkthrough as seen for this install. */
    fun finish() {
        viewModelScope.launch { childDevice.markPermWalkthroughSeen() }
    }

    /** System deep-link for the current step (walkthrough renders the copy). */
    fun settingsIntent(kind: PermissionKind): Intent = checker.settingsIntent(kind)
}
