package com.familyguard.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the gate screen renders (P6 + P18). */
data class PinGateUiState(
    val pinSetUp: Boolean = false,
    val entered: String = "",
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0L,
    val error: GateError? = null,
    /** True once this showing of the gate has been passed. */
    val unlocked: Boolean = false,
    val biometricAvailable: Boolean = false,
)

enum class GateError { WRONG, LOCKED, INVALID }

/**
 * Drives the P18 lock: shows whenever parent mode starts or the app returns
 * from background after > 2 minutes, and re-checks the salted PIN hash on
 * every 6th digit (lockout after 5 failures lives in [PinStore]).
 */
@HiltViewModel
class PinGateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pinStore: PinStore,
) : ViewModel() {

    private val mutable = MutableStateFlow(PinGateUiState())
    val state = mutable.asStateFlow()

    /** null until the store has spoken once — see setUp handling below. */
    private var wasSetUp: Boolean? = null

    init {
        viewModelScope.launch {
            combine(
                pinStore.isSetUp,
                pinStore.lockState,
                pinStore.biometricEnabled,
            ) { setUp, lock, biometric ->
                Triple(setUp, lock, biometric)
            }.collect { (setUp, lock, biometric) ->
                // Creating a PIN inside Settings must not instantly lock the
                // screen the user is standing on: stay unlocked for this
                // session; the next cold start / long background re-locks.
                val justCreated = setUp && wasSetUp == false
                wasSetUp = setUp
                mutable.update {
                    it.copy(
                        pinSetUp = setUp,
                        failedAttempts = lock.failedAttempts,
                        lockedUntilMs = lock.lockedUntilMs,
                        biometricAvailable = setUp && biometric && Biometrics.isAvailable(context),
                        unlocked = if (justCreated) true else it.unlocked,
                    )
                }
            }
        }
    }

    fun onDigit(digit: Char) {
        val s = mutable.value
        if (s.unlocked || s.error == GateError.LOCKED || s.entered.length >= PinPolicy.LENGTH) return
        val entered = s.entered + digit
        mutable.update { it.copy(entered = entered, error = null) }
        if (entered.length == PinPolicy.LENGTH) submit(entered)
    }

    fun onBackspace() {
        mutable.update {
            if (it.entered.isEmpty()) it else it.copy(entered = it.entered.dropLast(1))
        }
    }

    private fun submit(pin: String) {
        viewModelScope.launch {
            when (val result = pinStore.verify(pin)) {
                is PinCheck.Unlocked ->
                    mutable.update { it.copy(unlocked = true, entered = "", error = null) }

                is PinCheck.Wrong ->
                    mutable.update {
                        it.copy(entered = "", error = GateError.WRONG)
                    }

                is PinCheck.Locked ->
                    mutable.update {
                        it.copy(entered = "", error = GateError.LOCKED, lockedUntilMs = System.currentTimeMillis() + result.retryInMs)
                    }
            }
        }
    }

    /** Called by the wrapper when the app returns from a long background. */
    fun lock() {
        mutable.update { it.copy(unlocked = false, entered = "", error = null) }
    }

    /** BiometricPrompt verified the user — the system already did the work. */
    fun onBiometricSuccess() {
        mutable.update { it.copy(unlocked = true, entered = "", error = null) }
    }

    fun onBiometricError() {
        mutable.update { it.copy(error = GateError.WRONG) }
    }
}
