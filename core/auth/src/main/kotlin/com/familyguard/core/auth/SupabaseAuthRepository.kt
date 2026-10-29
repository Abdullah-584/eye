package com.familyguard.core.auth

import com.familyguard.core.common.AppError
import com.familyguard.core.common.AppResult
import com.familyguard.core.common.ErrorKind
import com.familyguard.core.domain.auth.AuthRepository
import com.familyguard.core.domain.auth.AuthState
import com.familyguard.core.domain.auth.SignInInput
import com.familyguard.core.domain.auth.SignUpInput
import com.familyguard.core.domain.auth.SignUpOutcome
import com.familyguard.core.domain.model.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AuthRepository backed by Supabase Auth + Postgrest.
 *
 * Notes on the tricky parts:
 *  * Sign-up returns [SignUpOutcome.ConfirmationRequired] whenever the project
 *    requires email confirmation, because no session exists in that case. We
 *    read the resulting session status instead of trusting the return value,
 *    which keeps this correct whether or not auto-confirm is enabled.
 *  * Every call is wrapped in [AuthErrorMapper.map] so callers never see a
 *    raw exception, and cancellation is never swallowed.
 */
@Singleton
class SupabaseAuthRepository @Inject constructor(
    private val client: SupabaseClient,
) : AuthRepository {

    override val state: Flow<AuthState> = client.auth.sessionStatus
        .map { status -> status.toAuthState() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun SessionStatus.toAuthState(): AuthState = when (this) {
        SessionStatus.Initializing -> AuthState.Initializing

        is SessionStatus.NotAuthenticated -> AuthState.SignedOut()

        is SessionStatus.Authenticated -> AuthState.SignedIn(session.user?.id.orEmpty())

        is SessionStatus.RefreshFailure -> AuthState.SignedOut(
            AppError(ErrorKind.UNKNOWN, message = "The saved session could not be refreshed"),
        )
    }

    override suspend fun signUp(input: SignUpInput): AppResult<SignUpOutcome> =
        withContext(Dispatchers.IO) {
            try {
                val cleaned = input.cleaned()
                client.auth.signUpWith(Email) {
                    email = cleaned.email
                    password = cleaned.password
                    data = buildJsonObject { put("display_name", cleaned.displayName) }
                }

                // auto-confirm on => a session exists already; otherwise the
                // user must open the emailed link before they can sign in.
                val signedIn = client.auth.sessionStatus.value is SessionStatus.Authenticated
                AppResult.Success(
                    if (signedIn) {
                        SignUpOutcome.SessionCreated
                    } else {
                        SignUpOutcome.ConfirmationRequired(cleaned.email)
                    },
                )
            } catch (throwable: Throwable) {
                AppResult.Failure(AuthErrorMapper.map(throwable))
            }
        }

    override suspend fun signIn(input: SignInInput): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val cleaned = input.cleaned()
                client.auth.signInWith(Email) {
                    email = cleaned.email
                    password = cleaned.password
                }
                AppResult.Success(Unit)
            } catch (throwable: Throwable) {
                AppResult.Failure(AuthErrorMapper.map(throwable))
            }
        }

    override suspend fun signOut(): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // LOCAL so signing out always succeeds, including offline.
                // The stored refresh token is discarded with the session.
                client.auth.signOut(SignOutScope.LOCAL)
                AppResult.Success(Unit)
            } catch (throwable: Throwable) {
                AppResult.Failure(AuthErrorMapper.map(throwable))
            }
        }

    override suspend fun loadProfile(userId: String): AppResult<Profile?> =
        withContext(Dispatchers.IO) {
            try {
                val profile = client.postgrest
                    .from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()
                AppResult.Success(profile)
            } catch (throwable: Throwable) {
                AppResult.Failure(AuthErrorMapper.map(throwable))
            }
        }
}
