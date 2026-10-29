package com.familyguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.data.mode.AppMode
import com.familyguard.core.data.mode.ModeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which mode this install runs in (PLAN A1): NONE until the chooser picks,
 * PARENT for the account holder, CHILD after pairing. The root NavHost is
 * driven by [mode] together with the auth phase.
 */
@HiltViewModel
class ModeViewModel @Inject constructor(
    private val modeRepository: ModeRepository,
) : ViewModel() {

    val mode: StateFlow<AppMode> = modeRepository.mode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppMode.NONE)

    fun becomeParent() = viewModelScope.launch {
        modeRepository.becomeParent()
    }
}
