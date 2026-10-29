package com.familyguard.feature.onboarding

import androidx.annotation.StringRes
import com.familyguard.core.common.ErrorKind
import com.familyguard.core.domain.auth.AuthField
import com.familyguard.core.domain.auth.AuthFieldError

/**
 * Maps validation problems and error kinds to string resources. Keeping this
 * in one place means no screen can ever show a raw server message.
 */
@StringRes
internal fun messageFor(field: AuthField, error: AuthFieldError): Int = when (field) {
    AuthField.DISPLAY_NAME -> when (error) {
        AuthFieldError.REQUIRED -> R.string.error_display_name_required
        AuthFieldError.TOO_LONG -> R.string.error_display_name_too_long
        else -> R.string.error_display_name_required
    }

    AuthField.EMAIL -> when (error) {
        AuthFieldError.REQUIRED -> R.string.error_email_required
        AuthFieldError.INVALID_FORMAT -> R.string.error_email_invalid
        AuthFieldError.TOO_LONG -> R.string.error_email_too_long
        else -> R.string.error_email_invalid
    }

    AuthField.PASSWORD -> when (error) {
        AuthFieldError.REQUIRED -> R.string.error_password_required
        AuthFieldError.TOO_SHORT -> R.string.error_password_too_short
        AuthFieldError.MISSING_LETTER -> R.string.error_password_missing_letter
        AuthFieldError.MISSING_DIGIT -> R.string.error_password_missing_digit
        else -> R.string.error_password_required
    }
}

@StringRes
internal fun bannerMessageFor(kind: ErrorKind): Int = when (kind) {
    ErrorKind.NETWORK -> R.string.error_banner_network
    ErrorKind.TIMEOUT -> R.string.error_banner_timeout
    ErrorKind.INVALID_CREDENTIALS -> R.string.error_banner_invalid_credentials
    ErrorKind.EMAIL_TAKEN -> R.string.error_banner_email_taken
    ErrorKind.EMAIL_UNCONFIRMED -> R.string.error_banner_email_unconfirmed
    ErrorKind.WEAK_PASSWORD -> R.string.error_banner_weak_password
    ErrorKind.RATE_LIMITED -> R.string.error_banner_rate_limited
    ErrorKind.NOT_CONFIGURED -> R.string.error_banner_not_configured
    ErrorKind.UNKNOWN -> R.string.error_banner_unknown
}
