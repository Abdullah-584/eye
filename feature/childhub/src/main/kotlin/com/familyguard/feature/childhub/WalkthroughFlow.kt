package com.familyguard.feature.childhub

import com.familyguard.core.permissions.PermissionKind

/**
 * The M2a walkthrough's pure logic — the part worth proving before any UI:
 * step order comes from [PermissionChecker.CHILD_STEPS], device admin cannot
 * be skipped, and nothing advances on a tap alone (M2-PLAN §1.1: the real
 * grant state is the only authority).
 */
object WalkthroughFlow {

    /** Only non-admin steps may be deferred; admin is the hard gate. */
    fun canSkip(kind: PermissionKind): Boolean = kind != PermissionKind.DEVICE_ADMIN

    /**
     * Which step is on screen: the first one neither granted nor skipped.
     * All of them satisfied → the index equals [granted].size (the summary).
     */
    fun activeIndex(granted: List<Boolean>, skipped: List<Boolean>): Int =
        granted.indices.firstOrNull { !(granted[it] || skipped[it]) } ?: granted.size

    fun isComplete(granted: List<Boolean>): Boolean = granted.all { it }

    /**
     * `false` when the child deferred something (still proceeding, honestly
     * labelled); "Continue" is what they press on the summary in that case.
     */
    fun exhaustedWithSkips(granted: List<Boolean>, skipped: List<Boolean>): Boolean =
        activeIndex(granted, skipped) == granted.size && !isComplete(granted)
}
