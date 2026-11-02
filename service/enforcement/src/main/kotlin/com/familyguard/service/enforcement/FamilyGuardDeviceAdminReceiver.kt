package com.familyguard.service.enforcement

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * M2a — the single_Admin administrative surface of the child app.
 *
 * What activation does (and nothing more):
 *  - the system marks this device "Parental controls active" in Settings,
 *  - uninstall / clear-data of FamilyGuard is blocked *while active*,
 *  - the description the child sees in the system dialog is
 *    [R.string.admin_description] — word-for-word the same facts the app
 *    itself says in the walkthrough and the persistent notification.
 *
 * What it deliberately does NOT do:
 *  - no `onDisableRequested` detour, countdown or password trap: the returned
 *    CharSequence is a dismissible warning the OS shows AND then deactivates
 *    anyway. The escape hatch stays open by design (M2-PLAN §2.3).
 *  - the event that reaches the parent is written by [EnforcementService]'s
 *    owner-side sampling loop, not from here — a receiver callback cannot be
 *    trusted to finish network work.
 */
class FamilyGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_requested_warning)
}
