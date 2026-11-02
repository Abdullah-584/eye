package com.familyguard.core.domain.model

/**
 * M2b — installed-app inventory + the parent's block intent.
 *
 * Two distinct facts, deliberately kept apart (docs/M2b-PLAN.md §2.4):
 *  - [InstalledApp] is what a paired device *reports* about the apps it can
 *    launch. The device owns it; the parent is read-only over inventory.
 *  - [AppBlock] is the *parent's* intent to block a package for a child. The
 *    parent owns it; the device is read-only over block state.
 *
 * Splitting them is structural, not cosmetic: Postgres RLS is row-level, so
 * one shared table would let the device's inventory-update policy overwrite
 * `blocked`. Two tables give the device no write path to block state and the
 * parent no write path to inventory.
 *
 * Inventory is keyed per (device, package) — a child can have two phones —
 * while blocks are keyed per (child, package): the enforcement set belongs to
 * the child, not to one handset, so blocking on phone A also blocks on phone B.
 */
data class InstalledApp(
    val childId: String,
    val deviceId: String,
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    val versionName: String?,
    /** Always true in M2b (only launcher-visible apps are scanned). */
    val launchable: Boolean,
    /** SHA-256 of the launcher icon; the parent draws a monogram instead. */
    val iconSha: String?,
    val firstSeen: String,
    val lastSeen: String,
    /** Null while installed; set the moment the device stops seeing it. */
    val removedAt: String?,
)

data class AppBlock(
    val childId: String,
    val packageName: String,
    val blocked: Boolean,
    /** Realtime ordering guard; higher wins. */
    val version: Int,
    val updatedBy: String?,
    val updatedAt: String,
)
