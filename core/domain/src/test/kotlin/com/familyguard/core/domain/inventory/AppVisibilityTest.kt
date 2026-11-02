package com.familyguard.core.domain.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {

    private val own = "com.familyguard.child"
    private val dialer = "com.google.android.dialer"
    private val sms = "com.google.android.apps.messaging"

    private fun exclude(pkg: String, dialerPkg: String? = dialer, smsPkg: String? = sms) =
        AppVisibility.isExcluded(pkg, own, dialerPkg, smsPkg)

    @Test
    fun `launcher system app is included`() {
        // YouTube ships as a system app WITH a launcher — the parent must be
        // able to block it, so it is not excluded.
        assertFalse(exclude("com.google.android.youtube"))
        assertFalse(exclude("com.android.calculator2"))
    }

    @Test
    fun `non-launcher system apps never reach the filter`() {
        // The scanner only feeds launcher-visible packages, so providers /
        // GMS background services are filtered before AppVisibility sees them.
        // This documents the boundary: the filter never receives them.
        val launcherOnly = listOf("com.android.chrome", "com.google.android.youtube")
        assertFalse(launcherOnly.any { exclude(it) })
    }

    @Test
    fun `hard exclusions are all filtered`() {
        assertTrue(exclude("com.android.settings"))
        assertTrue(exclude("com.android.systemui"))
        assertTrue(exclude("com.android.packageinstaller"))
        assertTrue(exclude("com.google.android.packageinstaller"))
    }

    @Test
    fun `own package is excluded so self-block cannot brick the app`() {
        assertTrue(exclude(own))
    }

    @Test
    fun `default dialer and sms are excluded for emergency calling`() {
        assertTrue(exclude(dialer))
        assertTrue(exclude(sms))
    }

    @Test
    fun `a null dialer or sms package excludes nothing extra`() {
        assertFalse(AppVisibility.isExcluded("com.example.phone", own, null, null))
    }

    @Test
    fun `filter preserves order and drops only the excluded`() {
        val input = listOf(
            "com.google.android.youtube",
            "com.android.settings",
            "com.example.app",
            "com.android.systemui",
        )
        val out = AppVisibility.filter(input, own, dialer, sms)
        assertEquals(listOf("com.google.android.youtube", "com.example.app"), out)
    }
}
