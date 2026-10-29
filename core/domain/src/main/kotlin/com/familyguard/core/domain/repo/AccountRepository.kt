package com.familyguard.core.domain.repo

import com.familyguard.core.common.AppResult

/**
 * Parent-facing account lifecycle (PLAN §6.3): data-subject access and
 * deletion. Both hit Edge Functions with the caller's JWT — RLS does not let
 * a client cascade-delete the family, so `delete-account` runs server-side.
 */
interface AccountRepository {

    /** `export-data` → the JSON representation of everything for this family. */
    suspend fun exportData(): AppResult<String>

    /**
     * `delete-account` → cascades auth.users, family, children, devices,
     * usage, locations and requests. Success also wipes the local Room cache.
     */
    suspend fun deleteAccount(): AppResult<Unit>
}
