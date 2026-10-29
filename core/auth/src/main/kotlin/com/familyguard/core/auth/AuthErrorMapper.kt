package com.familyguard.core.auth

import com.familyguard.core.common.AppError
import com.familyguard.core.common.ErrorKind
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a throwable from supabase-kt into an [AppError] the UI can localize.
 *
 * Two entry points on purpose:
 *  * [map] handles throwables. Cancellation is re-thrown, never swallowed.
 *  * [fromServerCode] is a pure function of the Supabase error code, which is
 *    what the interesting tests exercise (constructing a real RestException
 *    needs an HttpResponse).
 */
object AuthErrorMapper {

    fun map(throwable: Throwable): AppError = when (throwable) {
        // Cancelling a coroutine (screen change, VM cleared) is not a failure.
        is CancellationException -> throw throwable
        is RestException -> fromServerCode(throwable.error, throwable.description)
        is HttpRequestException -> AppError(ErrorKind.NETWORK, message = throwable.message, cause = throwable)
        is IOException -> AppError(ErrorKind.NETWORK, message = throwable.message, cause = throwable)
        else -> AppError(ErrorKind.UNKNOWN, message = throwable.message, cause = throwable)
    }

    fun fromServerCode(code: String?, detail: String? = null): AppError {
        val kind = when (code) {
            "invalid_credentials",
            "invalid_grant",
            "invalid_request",
            // Deliberately the same answer as wrong credentials: never confirm
            // that an address has an account.
            "user_not_found",
            -> ErrorKind.INVALID_CREDENTIALS

            "user_already_exists" -> ErrorKind.EMAIL_TAKEN
            "email_not_confirmed" -> ErrorKind.EMAIL_UNCONFIRMED
            "weak_password" -> ErrorKind.WEAK_PASSWORD

            "over_email_send_rate_limit",
            "over_request_rate_limit",
            "rate_limit_exceeded",
            -> ErrorKind.RATE_LIMITED

            else -> ErrorKind.UNKNOWN
        }
        return AppError(kind = kind, serverCode = code, message = detail)
    }
}
