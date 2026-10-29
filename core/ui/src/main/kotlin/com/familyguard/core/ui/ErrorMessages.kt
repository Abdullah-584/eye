package com.familyguard.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.familyguard.core.common.ErrorKind

/**
 * Maps an [ErrorKind] to its user-facing copy.
 *
 * ViewModels keep banners as plain strings so they stay free of Android
 * resources; [bannerMessage] turns that wire value back into text a person can
 * act on at the moment it is rendered.
 */
@StringRes
fun errorMessageRes(kind: ErrorKind): Int = when (kind) {
    ErrorKind.NETWORK -> R.string.banner_error_network
    ErrorKind.TIMEOUT -> R.string.banner_error_timeout
    ErrorKind.INVALID_CREDENTIALS -> R.string.banner_error_invalid_credentials
    ErrorKind.EMAIL_TAKEN -> R.string.banner_error_email_taken
    ErrorKind.EMAIL_UNCONFIRMED -> R.string.banner_error_email_unconfirmed
    ErrorKind.WEAK_PASSWORD -> R.string.banner_error_weak_password
    ErrorKind.RATE_LIMITED -> R.string.banner_error_rate_limited
    ErrorKind.NOT_CONFIGURED -> R.string.banner_error_not_configured
    ErrorKind.UNKNOWN -> R.string.banner_error_unknown
}

/**
 * Resolves the banner a screen should show.
 *
 * Banners are produced as `ErrorKind.name` (so the ViewModel never needs a
 * Context). Rendering that raw would show the user a string like "UNKNOWN",
 * so every screen passes its banner through here instead. An unrecognised
 * value is returned untouched — never lose a message we didn't anticipate.
 */
@Composable
fun bannerMessage(banner: String): String {
    val kind = ErrorKind.entries.firstOrNull { it.name == banner } ?: return banner
    return stringResource(errorMessageRes(kind))
}
