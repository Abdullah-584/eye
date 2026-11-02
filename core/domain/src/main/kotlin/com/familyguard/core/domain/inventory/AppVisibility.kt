package com.familyguard.core.domain.inventory

/**
 * M2b — the "user-relevant app" filter (docs/M2b-PLAN.md §1.2/§1.3).
 *
 * The scanner hands over everything that resolves `ACTION_MAIN` +
 * `CATEGORY_LAUNCHER`; this decides which of those a parent may see and block.
 * Pure and Android-free so the safety rules are unit-tested (§6.1).
 *
 * A launcher-visible app is *included* unless it is a hard exclusion. The hard
 * exclusions exist because blocking them would break something the product
 * already promised:
 *  - own package        — self-block bricks the child app;
 *  - Settings           — M2a's honesty contract requires the child can reach
 *                         Settings ▸ Apps ▸ Special access to deactivate admin;
 *  - SystemUI           — would hide the persistent "Parental controls active"
 *                         indicator we committed to;
 *  - default dialer/SMS — emergency calling/messaging;
 *  - package installers — the OS uninstall flow M2c depends on.
 *
 * These are filtered from the inventory entirely so they never appear as a
 * toggle that does nothing.
 */
object AppVisibility {

    /** Packages excluded no matter what. */
    private val STATIC_EXCLUSIONS = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    /** True when [packageName] must never appear in the parent's list. */
    fun isExcluded(
        packageName: String,
        ownPackage: String,
        defaultDialerPackage: String?,
        defaultSmsPackage: String?,
    ): Boolean = packageName == ownPackage ||
        packageName in STATIC_EXCLUSIONS ||
        (defaultDialerPackage != null && packageName == defaultDialerPackage) ||
        (defaultSmsPackage != null && packageName == defaultSmsPackage)

    /**
     * Keep only the apps a parent may legitimately see. Input order is
     * preserved so the UI's own sort is not disturbed.
     */
    fun filter(
        candidates: List<String>,
        ownPackage: String,
        defaultDialerPackage: String?,
        defaultSmsPackage: String?,
    ): List<String> = candidates.filterNot { pkg ->
        isExcluded(pkg, ownPackage, defaultDialerPackage, defaultSmsPackage)
    }
}
