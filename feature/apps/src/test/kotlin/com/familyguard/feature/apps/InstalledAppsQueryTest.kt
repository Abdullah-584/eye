package com.familyguard.feature.apps

import com.familyguard.core.domain.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsQueryTest {

    private fun app(name: String, pkg: String) = InstalledApp(
        childId = "c1",
        deviceId = "d1",
        packageName = pkg,
        appName = name,
        isSystem = false,
        versionName = "1.0",
        launchable = true,
        iconSha = null,
        firstSeen = "2026-10-01T00:00:00Z",
        lastSeen = "2026-11-01T00:00:00Z",
        removedAt = null,
    )

    private fun row(name: String, pkg: String, blocked: Boolean = false) =
        InstalledAppRow(app(name, pkg), blocked)

    private val rows = listOf(
        row("YouTube", "com.google.android.youtube"),
        row("Chrome", "com.android.chrome"),
        row("Minecraft", "com.mojang.minecraftpe"),
    )

    private fun visible(query: String) =
        rows.filter { InstalledAppsViewModel.matches(it, query) }

    @Test
    fun `blank query shows everything`() {
        assertEquals(3, visible("").size)
        assertEquals(3, visible("   ").size)
    }

    @Test
    fun `search matches label case-insensitively`() {
        assertEquals(listOf("YouTube"), visible("you").map { it.app.appName })
        assertEquals(listOf("YouTube"), visible("YOUTUBE").map { it.app.appName })
        // "CHROME" is only in Chrome's label/package; case must not matter.
        assertEquals(listOf("Chrome"), visible("CHROME").map { it.app.appName })
    }

    @Test
    fun `search matches package name too`() {
        assertEquals(listOf("Minecraft"), visible("mojang").map { it.app.appName })
        assertEquals(listOf("Chrome"), visible("com.android").map { it.app.appName })
    }

    @Test
    fun `no match yields empty list`() {
        assertTrue(visible("zzz-not-an-app").isEmpty())
    }

    @Test
    fun `query is a substring contains not a prefix`() {
        assertEquals(listOf("YouTube"), visible("tube").map { it.app.appName })
        assertEquals(listOf("Chrome"), visible("rome").map { it.app.appName })
    }
}
