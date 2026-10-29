package com.familyguard.core.data.internal

import com.familyguard.core.common.AppError
import com.familyguard.core.common.AppResult
import com.familyguard.core.common.ErrorKind
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * Runs [block] and classifies every throwable into an [AppError] so the UI
 * only ever sees ErrorKind (PLAN §7: no raw server text on screen).
 */
internal suspend fun <T> runApp(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (t: Throwable) {
    AppResult.Failure(t.toAppError())
}

internal fun Throwable.toAppError(): AppError = when (this) {
    is HttpRequestTimeoutException -> AppError(ErrorKind.TIMEOUT, cause = this)

    is HttpRequestException -> AppError(ErrorKind.NETWORK, cause = this)

    is PostgrestRestException -> {
        val text = message.orEmpty()
        val kind = when {
            "duplicate key" in text && "users" in text -> ErrorKind.EMAIL_TAKEN
            "rate" in text.lowercase() || "too many" in text.lowercase() -> ErrorKind.RATE_LIMITED
            "row-level security" in text || "permission denied" in text -> ErrorKind.UNKNOWN
            else -> ErrorKind.UNKNOWN
        }
        AppError(kind, serverCode = code, message = text, cause = this)
    }

    is java.net.UnknownHostException -> AppError(ErrorKind.NETWORK, cause = this)

    else -> AppError(ErrorKind.UNKNOWN, message = message, cause = this)
}
