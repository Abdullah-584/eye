package com.familyguard.core.auth

import com.familyguard.core.common.AppError
import com.familyguard.core.common.AppResult
import com.familyguard.core.common.ErrorKind
import com.familyguard.core.domain.auth.AuthField
import com.familyguard.core.domain.auth.AuthFieldError
import com.familyguard.core.domain.auth.AuthRepository
import com.familyguard.core.domain.auth.AuthState
import com.familyguard.core.domain.auth.FieldProblem
import com.familyguard.core.domain.auth.SignInInput
import com.familyguard.core.domain.auth.SignUpInput
import com.familyguard.core.domain.auth.SignUpOutcome
import com.familyguard.core.domain.model.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        val mutableState = MutableStateFlow<AuthState>(AuthState.SignedOut())
        override val state: StateFlow<AuthState> = mutableState.asStateFlow()

        var signUpCalls = 0
        var signInCalls = 0
        var signOutCalls = 0
        var profileCalls = 0

        var signUpResult: AppResult<SignUpOutcome> = AppResult.Success(SignUpOutcome.SessionCreated)
        var signInResult: AppResult<Unit> = AppResult.Success(Unit)
        var signOutResult: AppResult<Unit> = AppResult.Success(Unit)
        var profileResult: AppResult<Profile?> = AppResult.Success(Profile(id = "user-1", displayName = "Parent"))

        override suspend fun signUp(input: SignUpInput): AppResult<SignUpOutcome> {
            signUpCalls++
            return signUpResult
        }

        override suspend fun signIn(input: SignInInput): AppResult<Unit> {
            signInCalls++
            return signInResult
        }

        override suspend fun signOut(): AppResult<Unit> {
            signOutCalls++
            return signOutResult
        }

        override suspend fun loadProfile(userId: String): AppResult<Profile?> {
            profileCalls++
            return profileResult
        }
    }

    private fun AuthViewModel.enterValidSignInCredentials() {
        onEmailChange("parent@example.com")
        onPasswordChange("abc12345")
    }

    @Test
    fun `an invalid email shows a field error and never reaches the repository`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = AuthViewModel(repository)

            viewModel.onEmailChange("not-an-email")
            viewModel.onPasswordChange("abc12345")
            viewModel.signIn()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(0, repository.signInCalls)
            assertTrue(
                "expected an EMAIL/INVALID_FORMAT problem, got ${state.fieldErrors}",
                FieldProblem(AuthField.EMAIL, AuthFieldError.INVALID_FORMAT) in state.fieldErrors,
            )
            assertFalse(state.submitting)
        }

    @Test
    fun `a short password is rejected locally without a network call`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = AuthViewModel(repository)

            viewModel.onEmailChange("parent@example.com")
            viewModel.onPasswordChange("abc12")
            viewModel.signUp()
            viewModel.onDisplayNameChange("Sam")
            advanceUntilIdle()

            assertEquals(0, repository.signUpCalls)
            assertTrue(
                FieldProblem(AuthField.PASSWORD, AuthFieldError.TOO_SHORT) in viewModel.state.value.fieldErrors,
            )
        }

    @Test
    fun `a successful sign in reaches the signed in phase and loads the profile`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = AuthViewModel(repository)
            viewModel.enterValidSignInCredentials()

            viewModel.signIn()
            advanceUntilIdle()

            assertEquals(1, repository.signInCalls)

            repository.mutableState.value = AuthState.SignedIn(userId = "user-1")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AuthPhase.SIGNED_IN, state.phase)
            assertEquals("Parent", state.profile?.displayName)
            assertEquals(1, repository.profileCalls)
            assertFalse(state.submitting)
            assertNull(state.banner)
        }

    @Test
    fun `a failed sign in surfaces a banner and stays signed out`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository().apply {
                signInResult = AppResult.Failure(AppError(ErrorKind.INVALID_CREDENTIALS))
            }
            val viewModel = AuthViewModel(repository)
            viewModel.enterValidSignInCredentials()

            viewModel.signIn()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(ErrorKind.INVALID_CREDENTIALS, state.banner?.kind)
            assertEquals(AuthPhase.SIGNED_OUT, state.phase)
            assertFalse(state.submitting)
        }

    @Test
    fun `a profile load failure keeps the parent signed in and explains why`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository().apply {
                profileResult = AppResult.Failure(AppError(ErrorKind.NETWORK))
            }
            val viewModel = AuthViewModel(repository)

            repository.mutableState.value = AuthState.SignedIn(userId = "user-1")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AuthPhase.SIGNED_IN, state.phase)
            assertEquals(ErrorKind.NETWORK, state.banner?.kind)
            assertNull(state.profile)
        }

    @Test
    fun `a sign up that needs email confirmation shows the confirmation phase`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository().apply {
                signUpResult = AppResult.Success(SignUpOutcome.ConfirmationRequired("parent@example.com"))
            }
            val viewModel = AuthViewModel(repository)
            viewModel.onDisplayNameChange("Sam")
            viewModel.onEmailChange("parent@example.com")
            viewModel.onPasswordChange("abc12345")

            viewModel.signUp()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, repository.signUpCalls)
            assertEquals(AuthPhase.AWAITING_CONFIRMATION, state.phase)
            assertEquals("parent@example.com", state.confirmationEmail)
            assertFalse(state.submitting)
        }

    @Test
    fun `editing a field after a confirmation returns to the signed out phase`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository().apply {
                signUpResult = AppResult.Success(SignUpOutcome.ConfirmationRequired("parent@example.com"))
            }
            val viewModel = AuthViewModel(repository)
            viewModel.onDisplayNameChange("Sam")
            viewModel.onEmailChange("parent@example.com")
            viewModel.onPasswordChange("abc12345")
            viewModel.signUp()
            advanceUntilIdle()
            assertEquals(AuthPhase.AWAITING_CONFIRMATION, viewModel.state.value.phase)

            viewModel.onEmailChange("other@example.com")

            assertEquals(AuthPhase.SIGNED_OUT, viewModel.state.value.phase)
        }

    @Test
    fun `sign out asks the repository to sign out and clears the profile`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = AuthViewModel(repository)

            repository.mutableState.value = AuthState.SignedIn(userId = "user-1")
            advanceUntilIdle()
            assertEquals(AuthPhase.SIGNED_IN, viewModel.state.value.phase)

            viewModel.signOut()
            advanceUntilIdle()
            assertEquals(1, repository.signOutCalls)

            repository.mutableState.value = AuthState.SignedOut()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AuthPhase.SIGNED_OUT, state.phase)
            assertNull(state.profile)
        }

    @Test
    fun `a stored session is restored on startup without asking for credentials`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAuthRepository().apply {
                mutableState.value = AuthState.SignedIn(userId = "user-1")
            }

            val viewModel = AuthViewModel(repository)
            advanceUntilIdle()

            assertEquals(AuthPhase.SIGNED_IN, viewModel.state.value.phase)
            assertEquals(0, repository.signInCalls)
        }
}
