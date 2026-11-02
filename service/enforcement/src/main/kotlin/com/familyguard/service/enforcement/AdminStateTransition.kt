package com.familyguard.service.enforcement

/**
 * M2a — pure decision for the admin-watch loop, so the interesting rule is
 * unit-testable without Android (the loop itself just calls it):
 *
 *  - `previous == null` → we have never observed the admin here (fresh
 *    install/pairing): no report, whatever the current state is.
 *  - unchanged state → no report (sampling, not logging).
 *  - change → report `admin_disabled` / `admin_reenabled` to the parent.
 */
object AdminStateTransition {

    fun shouldReport(previous: Boolean?, active: Boolean): Boolean =
        previous != null && previous != active
}
