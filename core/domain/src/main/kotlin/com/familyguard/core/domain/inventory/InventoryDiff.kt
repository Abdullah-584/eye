package com.familyguard.core.domain.inventory

/**
 * M2b — the pure reconcile every detection layer converges on
 * (docs/M2b-PLAN.md §2.2): live receiver, manifest backstop and the periodic
 * worker all reduce to `reconcile(previous, scan)`. Unit-tested (§6.1).
 *
 * The three detection layers cannot each invent their own idea of "what
 * changed"; they hand a fresh [scan] here alongside what we already stored
 * ([previous]) and apply the result. Because the function is pure, an event
 * burst and a 6 h periodic scan produce identical output for identical state.
 *
 * Soft delete (`removed_at`) rather than row deletion (§2.3/§8.5): an
 * uninstall/reinstall churn collapses to one upsert, the parent can show
 * "removed" history, and `first_seen` survives a reinstall.
 */
object InventoryDiff {

    /** What we already know about one package, enough to reconcile it. */
    data class Previous(
        val packageName: String,
        /** Preserved across reinstalls; only a genuinely new app gets `now`. */
        val firstSeen: String,
        /** Null while installed; set the moment the app vanished. */
        val removedAt: String?,
    )

    /** A row to insert or refresh: always live ([removedAt] cleared). */
    data class Upsert(
        val packageName: String,
        val firstSeen: String,
    )

    data class Result(
        /** New + existing + reappearing apps, to be written as live rows. */
        val upserts: List<Upsert>,
        /** Live apps missing from [scan]; mark these `removedAt = now`. */
        val softDeletes: List<String>,
    ) {
        val isNoOp: Boolean get() = upserts.isEmpty() && softDeletes.isEmpty()
    }

    /**
     * Diff a fresh [scan] against [previous].
     *
     *  - package in scan, not in previous         → insert (firstSeen = now)
     *  - package in scan, in previous (live)      → refresh (firstSeen kept)
     *  - package in scan, in previous (removed)   → un-remove (firstSeen kept)
     *  - package not in scan, in previous (live)  → soft-delete (removedAt = now)
     *  - package not in scan, already removed     → no-op
     */
    fun reconcile(previous: List<Previous>, scan: List<String>, now: String): Result {
        val prevByPkg = previous.associateBy { it.packageName }
        val seen = scan.toSet()

        val upserts = scan.map { pkg ->
            val prev = prevByPkg[pkg]
            Upsert(packageName = pkg, firstSeen = prev?.firstSeen ?: now)
        }

        val softDeletes = previous
            .filter { it.removedAt == null && it.packageName !in seen }
            .map { it.packageName }

        return Result(upserts = upserts, softDeletes = softDeletes)
    }
}
