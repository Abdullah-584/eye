package com.familyguard.core.domain.model

import kotlinx.serialization.Serializable

/** M2c — parent-initiated request to uninstall a non-system app on the child's device.
 * Status lifecycle: pending → (confirmed/declined) → (done/failed/expired).
 * The child may only update `status`; the parent may create/update/delete within their family.
 */
@Serializable
data class UninstallRequest(
    val id: String,
    val childId: String,
    val packageName: String,
    val appName: String,
    val requestedBy: String,
    val status: UninstallRequestStatus = UninstallRequestStatus.PENDING,
    val resultMessage: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** Status of an uninstall request, mirroring the server `status` check constraint. */
@Serializable
enum class UninstallRequestStatus {
    PENDING,
    CONFIRMED,
    DECLINED,
    DONE,
    FAILED,
    EXPIRED,
}