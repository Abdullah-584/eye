package com.familyguard.service.enforcement

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M2-PLAN §2.2 / §1.3 — the admin-string coupling contract. The child can see
 * the device-admin wording in three placements: the system dialog description
 * (this module's manifest + strings), the walkthrough step (feature:childhub
 * strings), and the persistent enforcement notification (this module's
 * strings). Every child-visible placement must state the same three facts —
 * that "Parental controls active" is what the system shows, that uninstalling
 * is blocked while the admin is on, and that the child CAN deactivate it in
 * Settings ▸ Apps ▸ Special access ▸ Device admin apps, with the parent being
 * notified — so no placement may soft-pedal the honest picture.
 *
 * Project root is resolved from the gradle.properties property
 * org.admin.string.root, which should be set in the CI environment or
 * overridden via gradle.properties for local development.
 */
class AdminStringCouplingTest {

    private val projectRoot: String by lazy {
        val root = System.getProperty("org.admin.string.root")
        assertTrue("org.admin.string.root system property must be set", root.isNotEmpty())
        root
    }

    private val projectRootFile: File get() = File(projectRoot)

    private fun String.after(marker: String): String {
        val idx = indexOf(marker)
        assertTrue(marker + " not found in source", idx >= 0)
        return substring(idx + marker.length)
    }

    private fun assertPlacement(text: String, label: String) {
        val statusPhrase = "Parental controls active"
        val blockNeed = "uninstall"
        val blockPresent = "blocked"
        val deactPhrase1 = "Device admin apps"
        val deactPhrase2 = "parent"
        assertTrue("$label must name the admin status the system shows", text.contains(statusPhrase))
        assertTrue("$label must mention \"$blockNeed\" while admin is on", text.contains(blockNeed))
        assertTrue("$label must say \"$blockPresent\" while admin is on", text.contains(blockPresent))
        assertTrue("$label must keep the deactivation path visible ($deactPhrase1)", text.contains(deactPhrase1))
        assertTrue("$label must keep the deactivation path visible ($deactPhrase2)", text.contains(deactPhrase2))
    }

    @Test
    fun `system dialog description carries all three facts`() {
        val stringsPath = File(projectRootFile, "service/enforcement/src/main/res/values/strings.xml").readText()
        val adminDesc = stringsPath.after("<string name=\"admin_description\">")
        assertPlacement(adminDesc, "admin_description")
        val manifest = File(projectRootFile, "service/enforcement/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "manifest must reference @string/admin_description",
            manifest.contains("android:description=\"@string/admin_description\""),
        )
        assertTrue(
            "manifest must be wired to @xml/device_admin_familyguard",
            manifest.contains("@xml/device_admin_familyguard"),
        )
    }

    @Test
    fun `walkthrough step carries all three facts`() {
        val childhubPath = File(projectRootFile, "feature/childhub/src/main/res/values/strings.xml").readText()
        val walkthroughBody = childhubPath.after("<string name=\"perm_device_admin_body\">")
        assertPlacement(walkthroughBody, "perm_device_admin_body")
    }

    @Test
    fun `off-state notification is honest about uninstall and parent notification`() {
        val notifPath = File(projectRootFile, "service/enforcement/src/main/res/values/strings.xml").readText()
        val notifBody = notifPath.after("<string name=\"notif_admin_off_text\">")
        assertTrue("off-state notification must mention that uninstall is unblocked", notifBody.contains("uninstall"))
        assertTrue("off-state notification must say the parent was notified", notifBody.contains("parent"))
        val warnPath = File(projectRootFile, "service/enforcement/src/main/res/values/strings.xml").readText()
        val warnBody = warnPath.after("<string name=\"admin_disable_requested_warning\">")
        assertTrue("disable warning must say the parent is notified", warnBody.contains("parent"))
    }
}