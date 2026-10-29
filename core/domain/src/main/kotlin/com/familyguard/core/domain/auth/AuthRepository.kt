package com.familyguard.core.domain.auth

import com.familyguard.core.common.AppResult
import com.familyguard.core.domain.model.Profile
import kotlinx.coroutines.flow.Flow

/** What the app knows about the Supabase session right now. */
sealed interface AuthState {

    /** The client is still reading the persisted session from storage. */
    data object Initializing : AuthState

    /** No usable session. [reason] carries why, when we know. */
    data class SignedOut(val reason: com.familyguard.core.common.AppError? = null) : AuthState

    /** A valid session exists for this Supabase user. */
    data class SignedIn(val userId: String) : AuthState
}

/** What happened after a sign-up request. */
sealed interface SignUpOutcome {

    /** Email confirmation is required before a session exists. */
    data class ConfirmationRequired(val email: String) : SignUpOutcome

    /** The account was created and we are already signed in (auto-confirm). */
    data object SessionCreated : SignUpOutcome
}

/**
 * Auth operations the presentation layer depends on. The Supabase-backed
 * implementation lives in :core:auth; tests use a fake.
 */
interface AuthRepository {

    /** Mirrors the Supabase session status. Never emits null. */
    val state: Flow<AuthState>

    suspend fun signUp(input: SignUpInput): AppResult<SignUpOutcome>

    suspend fun signIn(input: SignInInput): AppResult<Unit>

    suspend fun signOut(): AppResult<Unit>

    /** Loads `public.profiles` for the signed-in user (null if not created yet). */
    suspend fun loadProfile(userId: String): AppResult<Profile?>
}
