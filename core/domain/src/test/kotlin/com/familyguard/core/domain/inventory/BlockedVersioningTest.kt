package com.familyguard.core.domain.inventory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedVersioningTest {

    @Test
    fun `first sight always applies`() {
        assertTrue(BlockedVersioning.shouldApply(incomingVersion = 1, localVersion = null))
    }

    @Test
    fun `equal version is applied (idempotent replay is harmless)`() {
        assertTrue(BlockedVersioning.shouldApply(incomingVersion = 3, localVersion = 3))
    }

    @Test
    fun `higher version is applied`() {
        assertTrue(BlockedVersioning.shouldApply(incomingVersion = 4, localVersion = 3))
    }

    @Test
    fun `out-of-order lower version is discarded`() {
        // A `block` (v2) arriving after an `unblock` (v3) must not re-block.
        assertFalse(BlockedVersioning.shouldApply(incomingVersion = 2, localVersion = 3))
    }
}
