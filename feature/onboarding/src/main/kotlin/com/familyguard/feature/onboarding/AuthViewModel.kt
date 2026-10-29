package com.familyguard.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyguard.core.common.AppError
import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.auth.AuthField
import com.familyguard.core.domain.auth.AuthRepository
import com.familyguard.core.domain.auth.AuthState
import com.familyguard.core.domain.auth.FieldProblem
import com.familyguard.core.domain.auth.SignInInput
import com.familyguard.core.domain.auth.SignUpInput
import com.familyguard.core.domain.auth.SignUpOutcome
import com.familyguard.core.domain.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which screen the onboarding flow should show. */
enum class AuthPhase {
    /** Still reading the persisted session; show the splash screen. */
    CHECKING_SESSION,

    SIGNED_OUT,

    /** Account created, waiting for the verification link. */
    AWAITING_CONFIRMATION,

    SIGNED_IN,
}

data class AuthUiState(
    val phase: AuthPhase = AuthPhase.CHECKING_SESSION,
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val fieldErrors: List<FieldProblem> = emptyList(),
    val banner: AppError? = null,
    val submitting: Boolean = false,
    val profile: Profile? = null,
    val confirmationEmail: String? = null,
) {
    fun errorFor(field: AuthField): List<FieldProblem> = fieldErrors.filter { it.field == field }
}

/**
 * Owns the sign-up / sign-in flow. The screens are dumb: they render
 * [state] and call the functions here.
 *
 * Rules encoded (and tested) here:
 *  * Invalid input is rejected locally and never reaches the repository.
 *  * The phase follows the repository's session state, except that a pending
 *    email confirmation keeps its own screen until the user edits a field.
 *  * A profile that fails to load leaves the parent signed in, with a banner
 *    explaining why, rather than bouncing them to sign-in.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { authState -> onAuthState(authState) }
        }
    }

    fun onDisplayNameChange(value: String) = _state.update {
        it.copy(
            displayName = value,
            phase = it.phase.afterEditing(),
            banner = null,
            fieldErrors = it.fieldErrors.without(AuthField.DISPLAY_NAME),
        )
    }

    fun onEmailChange(value: String) = _state.update {
        it.copy(
            email = value,
            phase = it.phase.afterEditing(),
            banner = null,
            fieldErrors = it.fieldErrors.without(AuthField.EMAIL),
        )
    }

    fun onPasswordChange(value: String) = _state.update {
        it.copy(
            password = value,
            phase = it.phase.afterEditing(),
            banner = null,
            fieldErrors = it.fieldErrors.without(AuthField.PASSWORD),
        )
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    fun signIn() {
        val input = SignInInput(_state.value.email, _state.value.password)
        val problems = input.validate()
        if (problems.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = problems) }
            return
        }
        _state.update { it.copy(fieldErrors = emptyList(), banner = null, submitting = true) }

        viewModelScope.launch {
            when (val result = repository.signIn(input.cleaned())) {
                is AppResult.Success -> _state.update { it.copy(submitting = false) }
                is AppResult.Failure -> _state.update { it.copy(submitting = false, banner = result.error) }
            }
        }
    }

    fun signUp() {
        val input = SignUpInput(_state.value.displayName, _state.value.email, _state.value.password)
        val problems = input.validate()
        if (problems.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = problems) }
            return
        }
        _state.update { it.copy(fieldErrors = emptyList(), banner = null, submitting = true) }

        viewModelScope.launch {
            when (val result = repository.signUp(input.cleaned())) {
                is AppResult.Failure -> _state.update { it.copy(submitting = false, banner = result.error) }

                is AppResult.Success -> when (val outcome = result.value) {
                    is SignUpOutcome.ConfirmationRequired -> _state.update {
                        it.copy(
                            phase = AuthPhase.AWAITING_CONFIRMATION,
                            confirmationEmail = outcome.email,
                            submitting = false,
                        )
                    }

                    // The session flow moves the phase to SIGNED_IN for us.
                    SignUpOutcome.SessionCreated -> _state.update { it.copy(submitting = false) }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            when (val result = repository.signOut()) {
                // The session flow moves the phase to SIGNED_OUT for us.
                is AppResult.Success -> Unit
                is AppResult.Failure -> _state.update { it.copy(banner = result.error) }
            }
        }
    }

    private fun onAuthState(authState: AuthState) {
        when (authState) {
            AuthState.Initializing -> _state.update { it.copy(phase = AuthPhase.CHECKING_SESSION) }

            is AuthState.SignedOut -> _state.update { current ->
                // "Confirm your email" has no session behind it, so keep that
                // screen until the user starts editing again.
                val phase =
                    if (current.phase == AuthPhase.AWAITING_CONFIRMATION) {
                        AuthPhase.AWAITING_CONFIRMATION
                    } else {
                        AuthPhase.SIGNED_OUT
                    }
                current.copy(
                    phase = phase,
                    submitting = false,
                    banner = authState.reason ?: current.banner,
                    profile = null,
                )
            }

            is AuthState.SignedIn -> {
                _state.update {
                    it.copy(
                        phase = AuthPhase.SIGNED_IN,
                        submitting = false,
                        banner = null,
                        password = "",
                        fieldErrors = emptyList(),
                    )
                }
                loadProfile(authState.userId)
            }
        }
    }

    private fun loadProfile(userId: String) {
        viewModelScope.launch {
            when (val result = repository.loadProfile(userId)) {
                is AppResult.Success -> _state.update { it.copy(profile = result.value) }
                is AppResult.Failure -> _state.update { it.copy(banner = result.error) }
            }
        }
    }

    private fun AuthPhase.afterEditing(): AuthPhase =
        if (this == AuthPhase.AWAITING_CONFIRMATION) AuthPhase.SIGNED_OUT else this

    private fun List<FieldProblem>.without(field: AuthField): List<FieldProblem> =
        filterNot { it.field == field }
}
