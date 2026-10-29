package com.familyguard.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.repo.PairingCode
import com.familyguard.core.domain.repo.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class PairingUiState(
    val loading: Boolean = true,
    val code: PairingCode? = null,
    val secondsLeft: Long = 0,
    val waiting: Boolean = false,
    val banner: String? = null,
)

/** P8 — parent side: generate a 6-char code (5 min TTL) and wait for the child device. */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state

    /** P8 entry: create a fresh code for the child the parent is pairing. */
    fun refresh(childId: String) = viewModelScope.launch {
        if (childId.isEmpty()) {
            _state.update { it.copy(loading = false, banner = "NO_CHILD") }
            return@launch
        }
        _state.update { it.copy(loading = true, banner = null) }
        when (val result = pairingRepository.createCode(childId)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(loading = false, code = result.value, waiting = true)
                }
                tick()
            }
            is AppResult.Failure -> _state.update {
                it.copy(loading = false, banner = result.error.kind.name)
            }
        }
    }

    private suspend fun tick() {
        while (true) {
            val code = _state.value.code ?: break
            val expires = runCatching { Instant.parse(code.expiresAt) }.getOrNull()
            val left = if (expires == null) 0L else (expires.epochSecond - Instant.now().epochSecond).coerceAtLeast(0L)
            _state.update { it.copy(secondsLeft = left) }
            if (left <= 0L) break
            kotlinx.coroutines.delay(1_000)
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }
}