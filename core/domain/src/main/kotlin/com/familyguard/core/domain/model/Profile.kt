package com.familyguard.core.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A parent's profile. Mirrors the `public.profiles` row, which the
 * `handle_new_user` trigger creates the moment Supabase Auth creates the user.
 */
@Serializable
data class Profile(
    @SerialName("id")
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("locale")
    val locale: String = "en",
)
