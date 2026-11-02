package com.familyguard.feature.childhub

import com.familyguard.core.permissions.PermissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughFlowTest {

    @Test
    fun `device admin is the only step that cannot be skipped`() {
        assertFalse(WalkthroughFlow.canSkip(PermissionKind.DEVICE_ADMIN))
        assertTrue(WalkthroughFlow.canSkip(PermissionKind.USAGE_ACCESS))
        assertTrue(WalkthroughFlow.canSkip(PermissionKind.NOTIFICATIONS))
        assertTrue(WalkthroughFlow.canSkip(PermissionKind.OVERLAY))
        assertTrue(WalkthroughFlow.canSkip(PermissionKind.BACKGROUND_LOCATION))
    }

    @Test
    fun `active step is the first ungranted and unskipped one`() {
        val granted = listOf(true, false, false)
        val skipped = listOf(false, true, false)
        assertEquals(2, WalkthroughFlow.activeIndex(granted, skipped))
    }

    @Test
    fun `summary shows when every step is granted or skipped`() {
        val granted = listOf(true, false, true)
        val skipped = listOf(false, true, true)
        assertEquals(3, WalkthroughFlow.activeIndex(granted, skipped))
        // ...and "exhausted with skips" keeps Continue enabled even though
        // device admin would refuse skip — this list has an ungranted step.
        assertTrue(WalkthroughFlow.exhaustedWithSkips(granted, skipped))
    }

    @Test
    fun `summary without skips means everything is granted`() {
        val granted = listOf(true, true, true, true, true)
        val skipped = listOf(false, false, false, false, false)
        assertEquals(5, WalkthroughFlow.activeIndex(granted, skipped))
        assertTrue(WalkthroughFlow.isComplete(granted))
        assertFalse(WalkthroughFlow.exhaustedWithSkips(granted, skipped))
    }

    @Test
    fun `nothing granted and nothing skipped starts at zero`() {
        assertEquals(0, WalkthroughFlow.activeIndex(listOf(false, false), listOf(false, false)))
    }
}
