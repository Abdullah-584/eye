package com.familyguard.core.domain.model

/**
 * M2a — device-admin state changes. The child is the *author* of these rows
 * (RLS: device writes its own pair); the parent is the *reader* and the only
 * side that can acknowledge them. Strings match the `admin_events.event_type`
 * CHECK constraint on the server exactly.
 */
object AdminEventType {
    const val DISABLED = "admin_disabled"
    const val REENABLED = "admin_reenabled"
}

data class AdminEvent(
    val id: String,
    val childId: String,
    val deviceId: String,
    val eventType: String,
    val occurredAt: String,
    val parentAckAt: String? = null,
    val createdAt: String = "",
)
