package com.familyguard.core.domain.model

import kotlinx.serialization.Serializable

/** M2c — result of an uninstall request, fed back from the child app to the parent. */
@Serializable
data class UninstallResult(
    val requestId: String,
    val status: UninstallRequestStatus,
    val resultMessage: String?,
)