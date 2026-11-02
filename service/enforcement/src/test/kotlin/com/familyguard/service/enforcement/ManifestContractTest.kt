package com.familyguard.service.enforcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M2b — CI manifest/contract assertions (docs/M2b-PLAN.md §6.3), same shape
 * as [AdminStringCouplingTest]: a pure JVM test reading source files, so the
 * package-visibility and block-wiring contracts are enforced in CI without an
 * emulator.
 *
 * Root is resolved from the `org.admin.string.root` gradle property (wired in
 * this module's testOptions), falling back to the build's own rootDir.
 */
class ManifestContractTest {

    private val projectRoot: File by lazy {
        File(System.getProperty("org.admin.string.root").orEmpty()).takeIf {
            File(it, "gradle.properties").exists()
        } ?: File("").absoluteFile.parentFile
    }

    private fun read(relative: String): String {
        val file = File(projectRoot, relative)
        assertTrue("missing source file: $relative", file.exists())
        return file.readText()
    }

    /** Every AndroidManifest.xml in the app, service, feature and core modules. */
    private fun allManifests(): List<File> =
        listOf("app-child", "app-parent")
            .plus(File(projectRoot, "service").listFiles()?.map { it.name } ?: emptyList())
            .plus(File(projectRoot, "feature").listFiles()?.map { it.name } ?: emptyList())
            .plus(File(projectRoot, "core").listFiles()?.map { it.name } ?: emptyList())
            .mapNotNull { module ->
                File(projectRoot, "$module/src/main/AndroidManifest.xml").takeIf { it.exists() }
            }

    @Test
    fun `child app declares launcher queries for package visibility`() {
        val manifest = read("app-child/src/main/AndroidManifest.xml")
        assertTrue("child manifest must declare <queries>", manifest.contains("<queries>"))
        assertTrue(
            "queries must include ACTION_MAIN",
            manifest.contains("android.intent.action.MAIN"),
        )
        assertTrue(
            "queries must include CATEGORY_LAUNCHER",
            manifest.contains("android.intent.category.LAUNCHER"),
        )
    }

    @Test
    fun `QUERY_ALL_PACKAGES is never declared`() {
        // It triggers Play policy review; the launcher-intent query covers the
        // requirement (docs/M2b-PLAN.md §2.1). Assert the FQ declaration, so a
        // doc comment mentioning the name does not fail the contract.
        val manifests = allManifests()
        assertTrue("expected to find app manifests", manifests.isNotEmpty())
        manifests.forEach { file ->
            assertFalse(
                "${file.name} must not request android.permission.QUERY_ALL_PACKAGES",
                file.readText().contains("android.permission.QUERY_ALL_PACKAGES"),
            )
        }
    }

    @Test
    fun `PACKAGE_FULLY_REMOVED backstop receiver has a package scheme`() {
        val manifest = read("service/enforcement/src/main/AndroidManifest.xml")
        assertTrue(
            "manifest must declare the PACKAGE_FULLY_REMOVED action",
            manifest.contains("android.intent.action.PACKAGE_FULLY_REMOVED"),
        )
        // The scheme is mandatory or the filter never matches (§2.2).
        assertTrue(
            "PACKAGE_FULLY_REMOVED filter needs <data android:scheme=\"package\"/>",
            manifest.contains("<data android:scheme=\"package\" />"),
        )
    }

    @Test
    fun `no manifest receiver claims PACKAGE_ADDED`() {
        // Dead code: PACKAGE_ADDED is not on Android 8+'s implicit-broadcast
        // exemption list, so a manifest receiver would never fire. Encode the
        // §2.2 correction so nobody re-adds it. Assert the FQ action string.
        val manifests = allManifests()
        assertTrue("expected to find app manifests", manifests.isNotEmpty())
        manifests.forEach { file ->
            assertFalse(
                "${file.name} must not declare android.intent.action.PACKAGE_ADDED " +
                    "(live detection is the runtime receiver in the service)",
                file.readText().contains("android.intent.action.PACKAGE_ADDED"),
            )
        }
    }

    @Test
    fun `enforcement feeds the observed block set not an empty set`() {
        val source = read(
            "service/enforcement/src/main/kotlin/com/familyguard/service/enforcement/" +
                "EnforcementService.kt",
        )
        // The wiring cannot silently regress to the pre-M2b hardcoded empty set.
        assertFalse(
            "EnforcementService must not pass blockedApps = emptySet()",
            source.contains("blockedApps = emptySet()"),
        )
        assertTrue(
            "EnforcementService must pass the observed blockedApps into evaluate",
            source.contains("blockedApps = blockedApps"),
        )
    }
}
