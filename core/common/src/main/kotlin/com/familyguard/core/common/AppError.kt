package com.familyguard.core.common

/**
 * Every failure the app can show to a user is reduced to one of these kinds.
 * UI code maps [kind] to a localized string, so no server text ever reaches
 * the screen untranslated.
 */
enum class ErrorKind {
    /** No usable connection to Supabase. */
    NETWORK,

    /** The request timed out. */
    TIMEOUT,

    /** Wrong email/password. Also used for "no such user" to avoid leaking
     *  which addresses have accounts. */
    INVALID_CREDENTIALS,

    /** The email already has an account. */
    EMAIL_TAKEN,

    /** The account exists but the verification link was never opened. */
    EMAIL_UNCONFIRMED,

    /** The password does not meet the server policy. */
    WEAK_PASSWORD,

    /** Too many attempts; the user should wait. */
    RATE_LIMITED,

    /** SUPABASE_URL / SUPABASE_ANON_KEY are missing from local.properties. */
    NOT_CONFIGURED,

    /** Anything we could not classify. */
    UNKNOWN,
}

/**
 * A classified failure. [serverCode] keeps the raw Supabase error code so it
 * can be written to logs without leaking the message to the user.
 */
data class AppError(
    val kind: ErrorKind,
    val serverCode: String? = null,
    val message: String? = null,
    val cause: Throwable? = null,
) {
    /** Worth retrying automatically when connectivity returns. */
    val isRetryable: Boolean
        get() = kind == ErrorKind.NETWORK || kind == ErrorKind.TIMEOUT
}
