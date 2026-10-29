# PLAN — Split FamilyGuard into two apps (parent / child)

**Phase 1 — planning only. No code changes.** Status: awaiting approval.

---

## 1. Current state: what exists today

### 1.1 Modules (from `settings.gradle.kts`)

| Kind | Modules |
| --- | --- |
| App | `:app` (`applicationId com.familyguard`, minSdk 26, targetSdk 35, versionName 0.1.0) |
| Core libs | `:core:common`, `:core:ui`, `:core:domain`, `:core:network`, `:core:auth`, `:core:database`, `:core:data`, `:core:permissions` |
| Features | `:feature:onboarding`, `:feature:pairing`, `:feature:family`, `:feature:rules`, `:feature:reports`, `:feature:location`, `:feature:requests`, `:feature:settings`, `:feature:childhub`, `:feature:enforcement` |
| Services | `:service:enforcement` (block screen + usage polling), `:service:location` (child-mode location) |
| Backend | `supabase/migrations` (16 tables), `supabase/functions` (`pairing-create`, `pairing-verify`, `pairing-revoke`, `device-heartbeat`, `time-request-decide`) |

### 1.2 Where parent and child logic currently live *together*

The project is already **one binary that behaves as two apps** at runtime, switched by a persisted flag:

| Concern | Today |
| --- | --- |
| Mode switch | `core/data/mode/AppModeStore.kt` — DataStore key `mode` = `NONE`/`PARENT`/`CHILD` (+ `child_id`, `device_id`). Entered via `ModeChooserScreen`; child mode is left only with the parent PIN (PLAN A1). |
| Navigation | `app/ui/FamilyGuardRoot.kt` — one `NavHost` with two route territories: parent routes (`home`, `requests`, `settings`, `create_family`, `add_child`, `devices`, `rules/{childId}`, `location/{childId}`, `reports/{childId}`, `pairing/{childId}`) and `CHILD_TERRITORY` (`child_home`, `child_rules`, `child_privacy`, `child_request`). |
| Services | `FamilyGuardRoot` starts `EnforcementService` + `LocationService` **only** when `mode == CHILD`, and stops them otherwise. |
| Permissions | Merged manifest already carries both roles' permissions: `:service:enforcement` declares `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`; `:service:location` declares fine/coarse/background location + `FOREGROUND_SERVICE_LOCATION`. The **parent app inherits all of them**, which it does not need. |
| PIN gate | `feature/settings/ParentGate` guards parent mode only. |
| Auth | Parent signs up/in with an account (`core:auth`, `feature:onboarding`). The child **never creates an account**: `pairing-verify` mints a dedicated device auth user (`app_metadata.role = "device"`), inserts the `devices` row with that `user_id`, and returns a device-scoped session the app imports via `client.auth.importSession(...)`. |
| Mixed modules | `:feature:pairing` contains **both** sides (`PairingScreen` = parent generates a code, `JoinScreen` + `JoinViewModel` = child consumes it). `:core:auth` contains both the session plumbing and the parent's sign-in state machine (`AuthViewModel`). |

### 1.3 What is shared vs. role-specific (per module)

**Shared (both roles):** `core:common`, `core:domain`, `core:ui`, `core:network` (Supabase client + session store), `core:database` (Room), `core:data` (repositories, `SyncEngine`), `core:permissions`.

**Parent-only:** `feature:onboarding`, `feature:family`, `feature:rules`, `feature:reports`, `feature:requests` (parent review list), `feature:location`, `feature:settings` (+ `ParentGate`), `feature:pairing`'s `PairingScreen`, `AuthViewModel` (sign-in state machine), `ModeChooserScreen`, `ModeViewModel`.

**Child-only:** `feature:childhub` (`ChildHomeScreen`, `ChildRulesScreen`, `WhatParentsSeeScreen`, `RequestTimeScreen`), `feature:enforcement` (`BlockScreen` + "ask for more time"), `service:enforcement`, `service:location`, `feature:pairing`'s `JoinScreen`/`JoinViewModel`.

---

## 2. Module split plan

```
:core:common   :core:domain   :core:ui   :core:network   :core:database
:core:data     :core:permissions   :core:auth            ← shared libraries
:feature:pairing-parent   (was feature:pairing, parent half)
:feature:join             (was feature:pairing, child half)
:service:enforcement  :service:location  :feature:childhub  :feature:enforcement  ← child-only libs
:feature:onboarding :feature:family :feature:rules :feature:reports
:feature:requests :feature:location :feature:settings                          ← parent-only libs

:app-parent   applicationId com.familyguard          label "FamilyGuard"
:app-child    applicationId com.familyguard.child    label "FamilyGuard Child"
```

Decisions and rationale:

1. **`com.familyguard` stays the parent package.** Existing parents update in place; no reinstall, session and Room data survive. Only child devices have to move.
2. **The role becomes a build-time fact, not a runtime flag.** `AppModeStore`, `ModeChooserScreen`, `ModeViewModel`, `Routes.MODE` and the `CHILD_TERRITORY` branch in `FamilyGuardRoot` are **deleted**, not carried over. Each app has its own `NavHost` with only its own routes. This also removes a real attack surface: today the child-mode flag lives in a DataStore the child can inspect/clear, and mode switching is gated by the PIN rather than by anything the platform enforces.
3. **`:core:auth` is split by responsibility**: `AuthRepository`/`AuthPhase`/session plumbing stay shared (the child app must observe its device session); `AuthViewModel` (email/password sign-in state machine) moves to `feature:onboarding`, which becomes parent-only.
4. **`:feature:pairing` is split** into `:feature:pairing-parent` (generate/show code) and `:feature:join` (enter code). The backend contract is untouched — see §4.
5. **Permissions become honest per app.** `app-parent`'s merged manifest ends up with `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` only. `app-child` carries usage access, overlay, location, boot, FGS types. This is both a privacy win and a Play-review signal (parents are not asked for location/usage access they never use).
6. **Two applicationIds ⇒ two Play listings eventually**, with independent `versionCode`s and a shared `versionName` scheme (e.g. both `0.2.0`). The child listing carries Play's parental-control/monitoring metadata (`IsMonitoringTool`), the parent listing does not.
7. **Icons/names are separate resources**, not `resourcePrefix` variants: `app-child` gets its own `ic_launcher`, its own `app_name`, and an `AndroidManifest` label that is *never* disguised (see §6).

Rough ownership after the split:

| App | Depends on |
| --- | --- |
| `:app-parent` | core:* (minus nothing), `feature:onboarding`, `feature:family`, `feature:rules`, `feature:reports`, `feature:requests`, `feature:location`, `feature:settings`, `feature:pairing-parent` |
| `:app-child` | core:* , `feature:join`, `feature:childhub`, `feature:enforcement`, `service:enforcement`, `service:location` |

---

## 3. Uninstall protection — the real options

> **Bottom line first:** there is **no Google Play–compliant way to hard-block uninstallation of a normal app on a consumer phone that the child physically controls.** Everything below is a choice about *which trade-off to take*. I recommend option **A**, with **B** documented as an explicit advanced/off-Play mode, and I recommend **against C**.

### 3.1 Device Admin API (`DevicePolicyManager`) — what people assume vs. what it does

What actually happens today (verified against the Android API reference and the device-administration guide):

- A **plain activated device admin** (we declare a `DeviceAdminReceiver`, the user ticks it in *Settings → Special app access → Device admin apps*) **cannot block uninstallation and cannot prevent its own deactivation.** The official docs say plainly: *"To uninstall an existing device admin app, users need to first unregister the app as an administrator."* The child simply unchecks us, then uninstalls. `onDisableRequested()` is a warning callback, not a veto.
- `DevicePolicyManager.setUninstallBlocked(admin, packageName, true)` — the API everyone points at — **requires the caller to be the device owner or profile owner** (or a delegate granted `DELEGATION_BLOCK_UNINSTALL`). Calling it as an ordinary activated admin fails. It is *not* an ordinary-admin capability.
- With the owner role, the block is real for that user: AOSP records `adb uninstall …` returning `DELETE_FAILED_OWNER_BLOCKED` for a blocked profile. It does **not** survive a factory reset (the reset removes the app and the policy with it), and it is not a guarantee against recovery-mode wipes or privileged device modification.

**Trade-off summary:** activation is easy and Play-legal, but as a *blocking* mechanism it is close to useless — it gives the child a one-tap off switch. Its only honest value is as the prerequisite plumbing for 3.2.

### 3.2 Device Owner mode — genuinely strong, genuinely constrained

`setUninstallBlocked` + non-deactivatable admin **do** work when we are the device owner. The costs:

- **Provisioning paths only:** `adb shell dpm set-device-owner …` (requires a device with no accounts signed in and, on most versions, no screen lock), or **QR / NFC / zero-touch provisioning during first-run setup after a factory reset**. You cannot attach an owner to a phone that is already set up with a Google account without wiping it.
- **One owner per device**, and it cannot be moved to another app without another factory reset.
- **It is a whole-device policy role**, not a "block uninstall" switch: the owner can (and OEMs expect it to) touch password, app-restriction and status-bar policies. A parental-control app claiming device owner will be scrutinized as a device policy controller, and Play's Malware policy explicitly warns against **using device-admin features to block uninstallation** (its carve-out is for management software shipped with the device, not for a consumer download).
- Distribution reality: a device-owner build effectively cannot be the Play listing; it is a sideloaded/enterprise-flavoured build.

**Is it realistic for a family setting up a kid's phone?** *Sometimes, and only in one shape:* the parent buys/ wipes the phone, and **during initial setup, before signing into any account, scans our QR code** to grant device owner. That is a real, repeatable flow for a tech-comfortable family. It is **not** realistic for: a phone already in use, a phone with a Google account already on it, handing the phone to a teenager to set up themselves, or any family that would be spooked by "this app now administers your whole device" (and it must not be hidden — see §6).

### 3.3 Accessibility-service-based uninstall interception — the risky one

An `AccessibilityService` can watch the uninstall/settings flow and back out of it, or swallow the confirmation. Facts:

- Play's Accessibility API policy **contains a parental-control carve-out**: the API may not be used to *"prevent the ability for users to disable or uninstall any app or service **unless authorized by a parent or guardian through a parental control app** or by authorized administrators through enterprise management software."* So the specific act is not automatically banned for us.
- But the rest of the policy still binds: the service must be declared, disclosed, non-deceptive, and used for a purpose consistent with its declaration. Play reviewers routinely reject accessibility use that exists only to defeat a system UI, and the stated penalty for deceptive/undeclared use is suspension, not just a rejection email.
- It is also **trivially defeated** (force-stop the app, revoke the accessibility grant, safe mode on many OEMs) and it produces the exact "why is this app intercepting my settings?" complaint that gets consumer parental-control apps removed.

**Verdict:** do not build the protection plan on this. It is the option most likely to get the child listing rejected on first submission.

### 3.4 Recommendation (explicit)

**A — Play-compliant default (recommended): make the app *undeniable* instead of *undeletable*.**

1. **No blocking mechanism on the Play build.** No accessibility interception, no claim that uninstall is prevented.
2. **Persistence:** the child app's foreground notification and its enforcement survive force-stop/force-kill as far as Android allows (boot receiver, `WorkManager` catch-up, exact re-schedule), and we detect the rest (below).
3. **Parent-side detection is the real protection:** the `device-heartbeat` already tells the parent app when a device was last seen. If the child app is uninstalled, force-stopped, or its permissions are revoked, the heartbeat and `device-heartbeat`-derived `last_seen_at` go stale → the parent dashboard shows an explicit **"Protection is off on <device>"** card with the last-seen time. A child removing protection is therefore *never silent*. Re-pairing is a 6-digit code away.
4. **Anti-friction, not anti-uninstall:** re-prompt for revoked usage-access/overlay/notifications permissions inside the child app, request exemption from battery optimization, and show the child exactly which protections are on or off (§6) — transparency that Play rewards rather than punishes.
5. **Optional device-owner build (B)** shipped *outside Play* (sideload/enterprise channel) for families who wipe the phone and provision it themselves, using `setUninstallBlocked` + owner provisioning QR. Same codebase, `app-kind` build flavor, clearly labelled in its own UI: "This build administers the device."

**Why A over B/C:** A is honest, Play-shippable, survives policy review, and covers the actual failure mode (a child quietly removing protection) via detection rather than an arms race we cannot win. B is offered as an opt-in for the one family scenario where it genuinely works. C is rejected for policy risk.

---

## 4. Pairing / auth across the app boundary

Good news: **the server contract barely changes**, because the pairing code is already the only credential that crosses the boundary.

| Step | Today | After the split |
| --- | --- | --- |
| Parent creates code | Parent session → `pairing-create` | unchanged (parent app) |
| Child joins | Anonymous call → `pairing-verify` mints device auth user, inserts `devices` row, returns device session → `importSession` | unchanged, but called from `app-child`; **plus** the request body carries `app_kind: "child"` and `package_name: "com.familyguard.child"` so the `devices` row records which binary is installed |
| Session | Child device user (`app_metadata.role = "device"`) scoped to one `child_id` via `devices.user_id`; RLS keys off it | unchanged |
| Heartbeat | `device-heartbeat` with `app_version`, `sdk`, `fcm_token` | unchanged + `package_name`, and a `protection_state` field (for §3.A.3) |
| Realtime | Both roles subscribe to rule/limit/schedule/time-request changes | unchanged; the child app only subscribes for *its* child, the parent for all children |

Consequences of the boundary:

- **No account on the child device, still.** `app-child` ships **no** sign-in/sign-up UI at all. First launch → enter 6-digit code → paired. This removes `feature:onboarding` and `AuthViewModel` from the child binary (and with them, the only way a child could create a parent account from their own device).
- **The parent PIN stays in the parent app** (`ParentGate`) guarding parent-mode navigation. In the child app there is no parent surface to guard, so the PIN is not needed there for navigation. If we implement §3 option B, the PIN is *not* the gate for deactivating the device owner (Android forbids it anyway); for option A we simply expose no "turn protection off" control that doesn't first verify a parent grant server-side.
- **`pairing-revoke`** (parent kills a device's refresh tokens) works unchanged against a separate binary — tokens are server-side.
- Optional, later: a "jump to the child app" intent from the parent dashboard (open `com.familyguard.child`'s status screen) via an explicit, permission-less `intent-filter` — nice-to-have, not required for M2.

---

## 5. Migration plan (existing single-app users + data)

### 5.1 On-device

| Who | What happens |
| --- | --- |
| Parent-mode install (`mode == PARENT`) | Updates to `:app-parent` under the **same `applicationId`**. Session, Room DB, PIN, and DataStore survive untouched. On first launch after update, one-time sheet: *"FamilyGuard now ships as two apps — your dashboard is unchanged."* |
| Child-mode install (`mode == CHILD`) | This is the breaking one: the package `com.familyguard` no longer contains enforcement. Two-step, both explicit: (1) on update, child-mode installs show a full-screen, honest notice — *"Protection moved to FamilyGuard Child. Install it to keep your parent's rules active."* — with a store link, and stop claiming to enforce; (2) after installing `app-child`, the device **re-pairs with a fresh code** generated by the parent (the parent already has the pairing screen; the code flow is 30 seconds). |
| Why re-pair and not copy the session? | The `devices` row must be re-created with `user_id`, `app_kind` and `package_name` for the new binary, and copying a session between apps requires a `ContentProvider` export of refresh tokens — an unnecessary secret-sharing surface between two packages. **All history is keyed by `child_id`, not by device**, so usage, locations, schedules, limits and time requests are preserved across the re-pair. Only the `devices` row is new (parent sees "paired again at <time>"). |

### 5.2 Supabase schema / RLS changes (small, additive)

1. `devices`: add `app_kind text not null default 'legacy'` (`child` for the new binary), `package_name text`, `protection_state jsonb` (e.g. `{"uninstall_protection":"none","usage_access":true,"overlay":true}`), `protection_updated_at timestamptz`. Backfill `app_kind = 'legacy'` for rows created before the split.
2. `device-heartbeat`: accept and persist `package_name` + `protection_state` (service-role function, no new trust surface).
3. `pairing-verify`: accept `app_kind` / `package_name` in the device object and write them into the inserted row.
4. **RLS: no behavioural change.** Child policies continue to key off `devices.user_id`; parent policies off `profiles`/`family_members`. Extend `supabase/tests/rls_profiles.sql` with cases for (a) a `devices` user reading only its own child's rows, (b) a parent reading any child of their family, (c) a device user failing to read `profiles`.
5. **If** we add a parent-visible "protection is off" signal as a table (recommended, rather than deriving it only from `last_seen_at`), it must be added to the `supabase_realtime` publication **with `REPLICA IDENTITY FULL`** (same reason as the existing `20261028000000_realtime_publication.sql` note: the client filters on `child_id`, which is not the PK).
6. Migrations are additive-only and apply to the live project before any app ships — no destructive rewrite, existing rows untouched.

### 5.3 Release mechanics

- Two `applicationId`s → two Play listings (eventually), independent `versionCode`s, same `versionName`.
- CI (`.github/workflows/ci.yml`) builds and tests **both** apps: `:app-parent:assembleDebug`, `:app-child:assembleDebug`, `testDebugUnitTest`, `lintDebug` on both.

---

## 6. Child-side transparency — non-negotiable requirements

These are acceptance criteria, not nice-to-haves. Any milestone that violates one fails review.

1. **Launcher:** `app-child` has a real, distinct, undisguised icon and the label **"FamilyGuard Child"**. It is never hidden, aliased, or given a decoy label. It appears in the app drawer and in *Settings → Apps* at all times.
2. **Persistent indicator:** a foreground-service notification is visible whenever enforcement or location is active, with plain-language text ("Parental controls are active — screen-time rules from your parent are being applied"), a tap-through to the child app's status screen, and it is **not** silently dismissible while the service runs. If `POST_NOTIFICATIONS` is denied, the status screen states that clearly and the parent dashboard reflects it.
3. **Rules screen:** `ChildRulesScreen` shows the child, live, exactly which rules are applied to them today (limits, schedules, web filters, location sharing on/off) plus "who set them" and "last updated".
4. **"What my parents see" screen:** `WhatParentsSeeScreen` stays, stating precisely what is collected (usage, location when enabled) and what is not.
5. **No disguise, ever:** no hidden icon, no masquerading app name, no removal from recent apps, no accessibility-based UI deception, no self-hiding of the notification. The child can always see that it is installed and what it does.
6. **Honest uninstall messaging:** the app never claims it *cannot* be removed. Its UI says what is true: *"If you remove this app, your parent will see that protection stopped."* (option A) or, in the device-owner build, *"Your parent set this phone up with device administrator access; removal is blocked by Android."*
7. **Permission truth screen:** a single screen listing every special permission the app holds (usage access, overlay, notifications, location, battery exemption) with current status — visible to the child.

---

## 7. Milestones and acceptance criteria

| # | Milestone | Acceptance criteria (all must pass) |
| --- | --- | --- |
| **M0** | Restructure: `settings.gradle.kts` gains `:app-parent`, `:app-child`; `:feature:pairing` split; `AuthViewModel` moved; `AppModeStore`/`ModeChooser` deleted. | Both apps assemble; `testDebugUnitTest` green; `lintDebug` green on both; CI updated and green. **Behaviour frozen**: parent and child flows still work exactly as before the split (they still live in `app-parent` temporarily if needed). |
| **M1** | `:app-parent` is parent-only: routes, manifest permissions, strings, icon. | Merged manifest of `app-parent` contains **no** `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, location or FGS permissions. Sign-in → dashboard → rules → reports → pairing-code generation all work on a device/emulator. |
| **M2** | `:app-child` end-to-end: join by code, child hub, block screen, both services. | Fresh device, no account: enter a code from `app-parent`, session imported, `devices` row created with `app_kind='child'`. Enforcement blocks an over-limit app, "ask for more time" reaches the parent's Requests tab, approving unblocks. Location fix visible in parent dashboard. |
| **M3** | Transparency compliance (§6) | Checklist 1–7 demonstrated on a device; screenshot of launcher, Settings→Apps, notification shade, rules screen, permission-truth screen. Notification survives swipe; tap-through works. |
| **M4** | Protection per approved option | **A:** force-stop / uninstall / revoke-usage-access on the child device produces a visible "Protection is off on <device>" state in the parent app within one heartbeat interval, with last-seen time. **B (only if approved):** device-owner build blocks `pm uninstall` (verify with `adb uninstall`) and cannot be deactivated in Settings; provisioning QR documented. |
| **M5** | Backend + migration | Migrations applied to the live project; RLS tests extended and green; heartbeat writes `protection_state`; existing parent install updates with session intact; child-mode install follows the documented re-pair path with history preserved. |
| **M6** | Play readiness / QA | Play `IsMonitoringTool` metadata + listing disclosures for the child app; permission declarations justified; OEM test matrix (stock Android, Samsung, Xiaomi, OnePlus) for background-service survival and battery optimization; both apps' `lintDebug` + unit tests green. |

Build order: **M0 → M1 → M2 → M3 → M4 → M5 → M6**, pausing for "continue" after each.

---

## 8. Risks

| Risk | Likelihood / impact | Mitigation |
| --- | --- | --- |
| **OEM kills the child app's foreground service** (Xiaomi MIUI, Huawei, Oppo aggressive standby) | High / high — enforcement silently stops | Detect via heartbeat staleness → parent "protection off" card (§3.A.3); per-OEM battery-optimization walkthrough inside the child app; M6 matrix. |
| **Child force-stops or clears data** | High / medium | Boot receiver + `WorkManager` catch-up already exist; heartbeat staleness surfaces it; clearing data drops the session → child app shows "not paired", parent sees device offline. |
| **Child revokes usage access / overlay / notifications** | High / medium | Permission-truth screen re-prompts; parent dashboard shows the degraded state from `protection_state`. |
| **Device admin deactivated (if we ship any admin build)** | Certain for plain admin / high if we promised blocking | Never promise blocking on the Play build (§3.4); device-owner build only, and say so in its own UI. |
| **Factory reset of the child's phone** | Medium / high | Nothing survives a wipe — by design of Android. Parent dashboard shows the device gone; re-pair after re-provisioning (option B: re-scan the setup QR *before* signing in). |
| **Play rejects the child listing** (device-admin/accessibility concerns) | Medium / high | Ship option A only on Play; no accessibility interception; monitoring metadata + disclosure; keep the child app's declared purpose narrow. |
| **Battery drain from usage polling + location** | Medium / medium | Poll cadence is already tuned (M5 work); location is foreground-gated with a documented disclosure; measure before M6. |
| **Two-listing maintenance burden** | Certain / low | Shared `core:*` libraries, one `versionName`, CI builds both; only two thin application modules differ. |
| **`app-child` package name collision / rename later** | Low / high (renaming a shipped `applicationId` breaks installs) | Freeze `com.familyguard.child` before the first public release. |
| **Child uses the parent app's listing to sign up as a parent** | Medium / low | Parent app already requires an account; the child app has no sign-up path at all after the split. |

---

Awaiting approval. Reply APPROVED or give changes.
