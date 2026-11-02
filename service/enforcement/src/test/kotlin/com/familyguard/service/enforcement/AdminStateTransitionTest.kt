package com.familyguard.service.enforcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminStateTransitionTest {

    @Test
    fun `never-observed baseline reports nothing`() {
        // Fresh install: sampling runs before the child ever activated the
        // admin. Nothing must be reported, even though admin is off.
        assertFalse(AdminStateTransition.shouldReport(previous = null, active = false))
        assertFalse(AdminStateTransition.shouldReport(previous = null, active = true))
    }

    @Test
    fun `unchanged state reports nothing`() {
        assertFalse(AdminStateTransition.shouldReport(previous = true, active = true))
        assertFalse(AdminStateTransition.shouldReport(previous = false, active = false))
    }

    @Test
    fun `both directions report`() {
        assertTrue(AdminStateTransition.shouldReport(previous = true, active = false))
        assertTrue(AdminStateTransition.shouldReport(previous = false, active = true))
    }
}
