package com.familyguard.core.domain.inventory

/**
 * M2b — the last-writer-wins guard for `blocked_apps` realtime delivery
 * (docs/M2b-PLAN.md §2.4).
 *
 * A realtime socket can deliver an UPDATE out of order: an old `block` landing
 * after a newer `unblock` would, if applied blindly, re-block an app the
 * parent just released. Every `blocked_apps` row carries a `version` that a
 * Postgres touch trigger bumps on each update, so the client applies an
 * incoming row only when `incoming >= local` — the exact rule the M1 limits
 * reducer already uses (`SyncEngine.applyLimit`).
 *
 * Pure and Android-free so the ordering rule is unit-tested (§6.1
 * `BlockedVersioningTest`). Equal versions are applied (an idempotent replay
 * is harmless); a strictly-lower version is discarded.
 */
object BlockedVersioning {

    /**
     * Should an incoming block row replace the local one?
     *
     * @param incomingVersion version carried by the realtime/pulled row.
     * @param localVersion    version already in Room, or null when we have
     *                        no row yet (first sight always applies).
     */
    fun shouldApply(incomingVersion: Int, localVersion: Int?): Boolean =
        localVersion == null || incomingVersion >= localVersion
}
