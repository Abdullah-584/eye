# FamilyGuard — M2 Plan (Permission Onboarding → App Rules → Dashboard Redesign)

> Status: PLANNING — no code written yet. This document follows the structure requested for
> this milestone phase 1. Nothing here is implemented until APPROVED.
>
> Grounded in the codebase as it stands today (M1 verified):
> - `:app-child` manifest currently carries only `INTERNET` + `ACCESS_NETWORK_STATE`; the
>   enforcement module (merged into `:app-child`) declares `SYSTEM_ALERT_WINDOW`,
>   `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`; the
>   location module declares fine/coarse/background location + `FOREGROUND_SERVICE_LOCATION`.
> - `EnforcementService` already uses `UsageStatsManager` (so `PACKAGE_USAGE_STATS` special
>   access is already a hard dependency of M1 enforcement) and already listens to
>   `postgres_changes` → `SyncEngine` → Room → enforcement loop for rule changes.
> - Existing Supabase tables: profiles, families, children, devices, rules, usage_events,
>   time_requests, pairing_codes (+ realtime publication migration).
> - Realtime path from M1 is proven end-to-end on-device (rule edit → child in <30 s).

---

## 0. On-record scope statement (this and every future milestone)

Out of scope for M2 and permanently flagged as Not Planned unless explicitly re-briefed:

1. **Live screen mirroring / screen capture / streaming** of the child device.
2. **Remote input injection / remote control** of the child device.
3. **Accessibility-service suppression of system security dialogs** — no intercepting the
   device-admin "Deactivate" button, no intercepting the system uninstall confirmation,
   no AccessibilityService used as a blocker at all.
4. **Hiding the child app**: the app icon stays visible, its purpose is not disguised, and the
   persistent monitoring indicator is visible to the child. The child app is *fully visible
   and self-explanatory* to the child — that is a design principle, not an accident.

If any later request asks for items 1–3, this plan flags it and stops rather than implements.

---

## 1. Permission onboarding flow (child app)

### 1.1 Trigger and lifecycle

- Runs once, on first launch after successful pairing (child-side pairing success in
  `JoinViewModel` → marks `onboarding_required` locally; also re-shown if any *required*
  permission is later detected as revoked).
- One permission per screen, forward-only with a visible progress header
  ("Step 2 of 5 — why we need this"), Back only within the intro of the current step.
- **Grant-state detection is authoritative, never optimistic**: each screen polls/observes
  the real permission check (`core/permissions/PermissionChecker` — extended for the new
  special accesses) both after returning from the system screen (`ON_RESUME` recheck) and
  via a short-lived repeating check. The "Next" button stays disabled until the check is
  actually granted. Closing settings and returning without granting keeps the screen
  waiting; there is a "Skip for now" that is visibly marked as leaving functions off
  (and re-surfaces later), except Device Admin, which cannot be silently skipped — see §2.
- Non-consecutive checks derive from a single source of truth (the M1 TEST trap applies after
  each fix is verified; each of the 5 screens gets a state test).

### 1.2 Order and rationale

| # | Permission / special access | Why at this position |
|---|---|---|
| 1 | **Usage Access** (`PACKAGE_USAGE_STATS`, Settings special-access screen, via `ACTION_USAGE_ACCESS_SETTINGS`) | Everything downstream — the app list shown to the parent (§3) and screen-time tracking — needs it. It also lets this screen show "we already read your installed apps' usage" truthfully. |
| 2 | **Notifications** (`POST_NOTIFICATIONS`, runtime dialog; on API < 33 auto-granted so the screen self-satisfies via the check) | Needed so the persistent monitoring indicator and later alerts (e.g. "your screen time is nearly up") actually appear; also so block events aren't invisible. |
| 3 | **Display over other apps** (`SYSTEM_ALERT_WINDOW`, via `ACTION_MANAGE_OVERLAY_PERMISSION` with the child package URI) | Required by the existing M1 block screen. Sensible after notification because the explanation can show "when a parent blocks an app you'll get a notice first". |
| 4 | **Background location** (fine → coarse → background chain: runtime request `ACCESS_FINE_LOCATION`, follow-up flow to Settings `APP详情`-style `always allow` via `startActivity(locationSettings)` / Android 11+ prompts via the standard `background location access` Settings path) | Explain plainly: location must keep working when the app is closed, because "yes, only while the app is open" would silently stop the family map whenever the child closes the app. The screen says exactly that in child-readable language. |
| 5 | **Device Admin** (§2) | Last: the strongest ask, and its explanation text can already be accurate about what activation adds ("Parental controls active", uninstall blocked without the parent PIN — and the deactivation path that remains). |

### 1.3 The persistent monitoring indicator

- Final onboarding step lands on a summary screen: a checklist of granted permissions with
  the statement that monitoring is active and what that means.
- Persistent object (not dismissible by the child): Android **notification** from the child
  app with
  - `setOngoing(true)`, `setShowWhen(true)`? — decision: `ONGOING_NOTIFICATION` owned by the
    enforcement foreground service (already exists from M1 style), text: "Parental controls
    active — this device is managed by your parent. Screen time, app rules and location
    sharing are on."
- Tapping the notification opens an in-app "What is this?" panel. Panel content is
  child-facing and honest: what is collected, what the parent sees, how the parent can
  unblock apps, and the one path the child has — a reminder that the parent controls this
  device's rules via FamilyGuard.
- Dismissibility: the notification is `ongoing` and is re-posted by the enforcement service;
  the in-app indicator row (visible on the child home tab) stays rendered as long as device
  admin is active. Removing the admin frees the UI (that is the OS-sanctioned escape hatch
  and we do not obstruct it — see §2.3), and the indicator turning off is then truthful.
- The indicator's exact wording is the **same string constants** as the device-admin
  description (§2.2) and the onboarding explanation text — three placements, one string
  table section, asserted by a test that builds the app and reads the strings back.

### 1.4 CI extension for this piece

- Existing manifest-graph assertions grow: child APK declares
  `PACKAGE_USAGE_STATS`, `RECEIVE_BOOT_COMPLETED` (already present), a `<receiver>` for the
  new `DeviceAdminReceiver` with `<meta-data>` policy XML, and a `M2`-labeled
  `android.app.admin.DEVICE_ADMIN_ENABLED` intent filter.

---

## 2. Device Admin: uninstall-block design

### 2.1 Mechanism

- `:service/admin` module (Android library, included by `:app-child` only):
  - `FamilyGuardDeviceAdminReceiver` (subclass of `DeviceAdminReceiver`).
  - `DevicePolicyManager.disableCamera` is NOT used (no camera restriction asked for).
  - Only what is needed: `USES_POLICY_MUST_REQUEST` to Unauthorized-install
    prevention is a *different* admin policy family — we do not use `addUserRestriction`.
    Admin purpose is exactly: **status "Parental controls active" + prevents `CLEAR_DATA` /
    uninstall while active.** Activation via `ACTION_ADD_DEVICE_ADMIN` with the receiver.
- Activation UX (child side): the admin screen's button launches the *system* activation
  dialog (`DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN`). We never invoke it in the
  background. No `silent activation` without the user's system-dialog tap.

### 2.2 Exact user-facing text (system dialog vs app text — must match)

The system `DeviceAdminReceiver` admin description is what the child sees on the system
dialog. Our `device_admin_description.xml` will say, verbatim-tracked in the repo tests:

> "FamilyGuard parental controls. When active: this device is marked 'Parental controls
> active'. Uninstalling FamilyGuard and clearing its data is blocked until this admin is
> turned off. FamilyGuard's app rules, screen-time limits and location sharing keep working
> while this is on."

How-to-deactivate is stated to the child in the app's own admin info screen —
Settings ▸ Apps ▸ Special app access ▸ Device admin apps ▸ FamilyGuard, or through
Settings search for "Device admin" — text present in the same strings table. The UI text
matches the system dialog's framing part-for-part (parent's controls is the metric).
Admin policies activated in the receiver XML: `USES_POLICY_LIMIT_PASSWORD`? No — kept to:
`USES_POLICY_APP_DISABLE`? No — the receiver activates exactly ONE policy:
`USES_POLICY_DISABLE_KEYGUARD_FEATURES` NOTHING. Final decision, minimal set:
- `USES_POLICY_FORCE_LOCK` (allows the child device's lock button in *our own* UI not
  needed) — dropped.
- **Activated: none of the intrusive policies.** The receiver activates with an empty
  profile-supporting set is invalid; Android requires at least one policy. Activated set:
  `USES_POLICY_LIMIT_PASSWORD` is the lightest existing knob that grants "device admin"
  semantics without any extra system capability the child experiences. We do not actually
  change the password with it; the manifest declares it purely so the admin is a valid
  admin. Documented in code comment and here as a knowing trade-off; the alternative
  (`USES_POLICY_MUST_REQUEST`) looks heavier to the child so it is avoided.
  *(If testing on a target device shows LIMIT_PASSWORD overhead in the admin list, the
  fallback is `USES_POLICY_DISABLE_CAMERA` — also unused functionally but recognisable —
  chosen at build-prove time.)*

### 2.3 The real limitation, on record

- Any child (or anyone holding the child device) can go to
  **Settings ▸ Apps ▸ Special access ▸ Device admin apps** and turn FamilyGuard's admin
  off. Once off, uninstall via the standard uninstaller is possible. This is OS-guaranteed
  and **is not fought with AccessibilityService interception of the Deactivate button**
  (explicitly out of scope, §0.3).
- What we *do* when deactivation happens:
  - `FamilyGuardDeviceAdminReceiver.onDisableRequested()` is still allowed to
    `return` a short CharSequence warning that the system shows as a dismissible dialog
    before deactivation: default `onDisableRequested` return of null shows nothing. We
    return an honest two-liner: "Turning this off stops parental controls on this device.
    Your parent is notified. To unblock or uninstall, ask your parent on the FamilyGuard
    parent app." (No gate, no fake password, no delay — the OS disables it anyway; the
    dialog is warning-only, per policy.)
  - After deactivation, if the app can still run (it is not force-stopped), the running
    enforcement service detects `isAdminActive == false` on its next state sample
    (≤5 s cadence) and — using the existing Supabase data session —
    writes an `admin_events` row + posts a realtime event `/child alert path` to the
    parent channel (see §2.4). `onDisableRequested` itself cannot network reliably, so the
    detection is owner-side, not hook-side.
  - Parent side: `Requests` tab gets an alert card "Device admin turned off — FamilyGuard
    can be uninstalled on <child device>" + FCM-style push (Supabase realtime is enough
    for a foregrounded parent app; push-to-parent through an Edge Function + web push
    hook is an M3+ nice-to-have noted in §5).
- **Uninstall time-bomb we deliberately do NOT use**: repeating-alarm or POC-driven
  reactivation via `ACTION_ADD_DEVICE_ADMIN` needs no special permission and re-activates
  silently — that is a known trick, and it is also the pattern that reports to Play
  poorly and is deceptive. If the child turns admin off, our system *observes and
  reports*, never re-activates behind their back.

### 2.4 Notification path (child → parent), explicitly planned

1. Detection (above) → child writes `admin_events(child_id, event_type='admin_disabled',
   occurred_at)` with device RLS.
2. Parent dashboard is subscribed (M1 mechanism) to `admin_events` on the family channel
   over `postgres_changes`. Card raises immediately when both are online.
3. Fallback poll: parent app's `RequestsViewModel` already polls `time_requests`; the same
   poller adds `admin_events` at the same cadence so a realtime hiccup still surfaces
   admin loss within the standard poll interval (≤60 s).
4. If the child device is offline when the row is written — the row *is* the alert; parent
   sees it after the child app's next sync, and its `pending` model keeps it visible until
   the parent acknowledges (read/ack column, `admin_events.parent_ack_at`).

---

## 3. Installed-app inventory sync

### 3.1 Child-side query and filtering

- Source: `PackageManager.getInstalledPackages(GET_META_LIST)` → `ApplicationInfo`,
  flag-filtered. Filter to keep the parent list meaningful:
  - Exclude: `[FLAG_SYSTEM]` only if the app has **no launcher activity**
    (true framework/system infrastructure: Providers, Print services, AOSP hidden apps).
  - Include `FLAG_SYSTEM` apps that ARE launcher-visible (YouTube, Calculator... the parent
    still needs to block these!) — this is the decision that avoids a meaningless
    200-app dump while still listing real world apps.
  - Explicitly exclude `(a)android.` `com.android.providers.*` and our own package.
- Columns per app: `package_name` (PK with child_id), `app_name` (label), `is_system`,
  `version_name`, `launch_intent_present`, `first_seen`, `last_seen`.
- **Icon storage**: no blob in Postgres. Icons are hashed into `icon_sha` and cached on the
  parent side from the child's R2/storage bucket *only if the bucket is configured* —
  M2 default is **no icons from inventory** (parent list uses the Android default box
  with a colored monogram). This keeps the table light and the sync cheap; icon culture
  is an M2.5 stretch listed in §6.
- **Sync cadence**: full-upsert on child app cold start + `PACKAGE_ADDED`/`PACKAGE_REMOVED`
  broadcast receiver (with `setQueryPackage`) → immediate delta, plus a WorkManager
  repeat at 6 h (makes `last_seen` the heartbeat that says "inventory current as of X").
- Same-run honesty: sync uses usage data to mark `used_today` (client-side enriched read,
  not stored, since `usage_events` already carries per-app use).

### 3.2 Schema

```sql
create table installed_apps (
  id uuid primary key default gen_random_uuid(),
  child_id uuid not null references children(id) on delete cascade,
  package_name text not null,
  app_name text not null default '',
  is_system boolean not null default false,
  version_name text,
  launchable boolean not null default true,
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  unique (child_id, package_name)
);
create index installed_apps_child_idx on installed_apps(child_id);
-- removed apps: rows are deleted on PACKAGE_REMOVED (plus a tombstone count is
-- preserved in a rate-limit-friendly way ONLY via last_seen on a soft-delete column).
alter table installed_apps add column removed_at timestamptz;
```

`blocked_apps` table (owns the parent intent, see §4):

```sql
create table blocked_apps (
  child_id uuid not null references children(id) on delete cascade,
  package_name text not null,
  blocked boolean not null default true,
  updated_by uuid not null,           -- parent profile id
  updated_at timestamptz not null default now(),
  primary key (child_id, package_name)
);
```

### 3.3 RLS (written and tested in the same milestone — rule honored)

- `installed_apps`: child device session (its `devices.user_id` — the M1 device-scoped
  user pattern) may insert/upsert/select **its own** child's rows; parent may
  `select` only rows whose child belongs to a family the caller is a parent of.
  No anonymous write/select. Existing `is_parent_of_child` RLS helper from the M1
  migration is reused.
- `blocked_apps`: parent (only parents of the family) may insert/update/delete; child
  device user may **select only** the blocked state (never set it). The enforcement list
  derives from this read-only access — child cannot self-unblock.
- Tests land in `supabase/tests/rls_m2.sql`, in the same runner/format as the existing
  `run-rls-tests.mjs` pattern, covering the four-way matrix: parent of another family X,
  parent of this family, the child device user, anon.

---

## 4. Instant block/unblock

1. Parent toggles in the new App Rules screen → optimistic local write to the Room
   parent-side cache and immediate POST to `blocked_apps` (upsert on conflict).
2. Supabase `postgres_changes` push on the family channel → child device's existing
   `SyncEngine` path (already provisioned in M1 for rules) → Room write.
3. `EnforcementService` — already listening for room changes in its M1 loop — picks the row
   up on its next Room-driven bookmark; **and for immediacy**, the same realtime collector
   posts an in-process `EnforcementRefresh` signal so the blocked set changes without
   waiting for the next 5-second scan; the block screen appears the next time the child
   launches that package.
4. Conflict/edge rules, explicit:
   - Unblock while the block screen is open: overlay is torn down on the same refresh and
     the app is usable immediately.
   - Block an app the child currently has open: first foreground-check of that package
     (the existing M1 scan) shows the block screen; we do NOT kill processes (design: no
     `ActivityManager.killBackgroundProcesses` or roots-of-any-kind — the overlay shows on
     next interaction with that app, which is M1 behavior carried forward).
   - Child offline: parent's toggle still succeeds; child pulls the row
     on next activity (same row, same semantics — first-write wins because parent-only
     writes make conflict moot).
   - New app installed after initial inventory: appears in the parent list with
     `blocked=false` default; instant-block path works on it immediately afterward.
5. `TimeRequest`-style UX niceties (reuse M1 components): a small "Blocked now" chip and a
   "Last sync 12 s ago" so the parent can always tell live vs stale.

---

## 5. Uninstall request flow

Asked for by parent, executed by child, **no faking of the OS dialog**:

1. Parent taps **Request uninstall** on a non-system app (only shown when
   `is_system=false`; system apps are grayed out with the honest tooltip "System apps
   can only be disabled by the OS user, they cannot be uninstalled remotely").
2. Row in `uninstall_requests`:
   ```sql
   create table uninstall_requests (
     id uuid primary key default gen_random_uuid(),
     child_id uuid not null references children(id) on delete cascade,
     package_name text not null,
     app_name text not null default '',
     requested_by uuid not null,
     status text not null default 'pending'  -- pending|confirmed|declined|done|failed|expired
   , result_message text,
     created_at timestamptz not null default now(),
     updated_at timestamptz not null default now()
   );
   ```
   RLS same-four-way matrix as §3.3; child may update `status` of requests that belong to
   its device only, may not create them.
3. Child-side prompt: realtime row insert → in-app banner + heads-up notification
   "Your parent is asking to remove <App> — allow?" with Confirm / Dismiss buttons.
   Auto-confirm mode: hidden in `local.properties`/debug only; production build always
   confirms-by-tap. We do NOT auto-fire dialogs the child never saw.
4. On Confirm, child app launches:
   ```
   Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
   ```
   The manifest does NOT use `ACTION_UNINSTALL_PACKAGE`-with-flags — the extra
   `EXTRA_RETURN_RESULT` (the `UNINSTALL_STATUS` result code path is deprecated on
   modern targets) is replaced by tracking:
   - status set `confirmed` → prompt launches the system dialog → we register an
     `PackageManager.installUninstallFlow` events listener? — no deprecated API.
     We schedule a one-shot `PackageRemovedReceiver`-equivalent:
     - `onReceive` for `ACTION_PACKAGE_REMOVED` + same `package_name` → `status=done`.
     - if the child cancels the system dialog: we bind via
       `PackageInstaller.uninstall` (API 30+, child app is *not* device-owner, so this
       is NOT available) — **explicitly noted: with Device Owner this becomes silent
       earlier in M4; in M2 it does not happen.**
     - The cancelled case surfaces as: system dialog was confirmed but the OS shows
       the child's final "Uninstall <App>?" — if they then cancel it, the app stays.
       We mark `status=declined` after a **45 s expiry timer** if no
       `ACTION_PACKAGE_REMOVED` fires and the child resolves the prompt (single-shot
       JobScheduler). `failed` = `ACTION_PACKAGE_REMOVED` never fired + app still in
       inventory (parent sees "still installed — try again").
5. Results flow back, parent card reflects it. Parent may re-request after a cooldown
   (5 min, prevents nags-till-click-farm behavior... and children can be released from a
   nag loop).

**On record**: this is request + child-side confirmation. The OS uninstall dialog cannot
be skipped in M2 (only with Device Owner provisioning, which is M4 scope).

---

## 6. Parent dashboard redesign

### 6.1 Target IA (top-level sections)

| Tab | Purpose | Built in this milestone? |
|---|---|---|
| **Overview** | Per child: today's total screen time vs daily limit, top apps, active alerts (time requests, admin events), rule status chips. | Rebuilt (data all exists) |
| **Devices** | Existing devices screen, restyled, becomes "add child / pair another device" landing. | Restyled |
| **App Rules** | New: installed-app list per child with instant block toggle, "Request uninstall" for non-system apps, search + sort. | New — §3/§4 |
| **Location** | Existing M1 map (+history list). | Restyled shell only |
| **Reports** | M1 usage charts, expanded with app breakdown + daily bars. | Extended |
| **Requests** | Time requests (M1) + uninstall requests (M2) + admin events (M2) in one inbox. | Extended |
| **Settings** | Existing settings (PIN, name, sign out) + new "Child timezone / notification permissions" info panel. | Extended |

### 6.2 Visual direction

- **Material 3 with dynamic color on 12+** and a committed fallback palette
  (`dynamicLightScheme`/`dynamicDarkScheme` → brand seed `#4F6BFF` family) — cards on
  `surfaceContainer`, expressive corner shape tokens, tonal elevation over shadows.
- Typography: expressiveness keylines (`headlineMedium` for Overview greeting, `titleMedium`
  per card, large tabular numerals for time).
- Charts: existing usage bar chart reused, wrapped in a `ReportsCard` with empty state.
- Empty/skeleton/loading: three states explicitly designed per card — `EmptyCard`
  ("No app rules yet — pair a device first"), thin shimmer skeletons while loading,
  inline error card with retry.
- Motion: standard `animateContentSize` on toggles; no custom physics; respects
  `prefers-reduced-motion`? — respect `ANIMATOR_DURATION_SCALE`-based system setting via
  Compose default (Compose honors it by default for animate* APIs).
- Accessibility: min 48 dp targets, contentDescription for all toggle states
  ("Blocks <App> for <Child> — currently enabled"), semantic states.

### 6.3 Navigation pattern decision

- 7 top-level destination count is above the 5-slot bottom-nav sweet spot.
- Chosen: **bottom NavigationBar with 5 primary tabs (Overview, App Rules, Requests,
  Location, Reports) + an "More" overflow inside which Devices and Settings live**
  (standard 5-tab + more pattern; Android-recommended above 5).
- Phone-only for now: **no nav rail unless a tablet/layout-class reshape is on record**
  (rail becomes the M3 stretch goal if tablets matter).
- Predictive back + save-restore of tab state are free-level wins from Navigation +
  Compose and asserted in the test.

### 6.4 Screen mocks (text)

**Overview**
```
┌──────────────────────────────────────────────┐
│ Good evening, Abdullah            [avatar]   │
│ ──────────────────────────────────────────── │
│ ┌─.child selector─.  (EmuKid ▾)              │
│ ┌──────────────────────────────────────────┐ │
│ │ Screen time today                        │ │
│ │ 2 h 14 m                                 │ │
│ │ ████████████░░░░░░  3 h limit            │ │
│ │ ● on budget    [▶ view app rules]        │ │
│ └──────────────────────────────────────────┘ │
│ ┌ Active alerts ───────────────────────────┐ │
│ │ ⚠ 2 time requests waiting   →            │ │
│ │ ⚠ Admin off on EmuKid's phone  →         │ │
│ └──────────────────────────────────────────┘ │
│ Today · Top apps                             │
│ [YouTube 42m] [Chrome 11m] [Minecraft 2h…]   │
└──────────────────────────────────────────────┘
```

**App Rules (list item)**
```
┌──────────────────────────────────────────────┐
│ [monogram] YouTube           ⏱ 42 m today    │
│             [ Unblock ▢ ]   (toggle)         │
│             Blocked-away badge if blocked    │
└─ swipe or menu: Request uninstall ────────────┘
```
**Single-app detail**
```
┌──────────────────────────────────────────────┐
│ YouTube (com.google.android.youtube)         │
│ Status: BLOCKED since 18:04 by parent        │
│ [ Block now ▾ / Unblock ]                    │
│ [ Request uninstall ]  (non-system only)     │
│ Recent rule history for this app             │
└──────────────────────────────────────────────┘
```

---

## 7. Milestone breakdown with acceptance criteria

### M2a — Child permission onboarding + Device admin
- Onboarding flow shows 5 steps in §1.2 order; a step cannot be marked "done" by a
  UI tap alone — the tracked Android permission probe must return granted.
- Device admin: activation via system dialog only; `FamilyGuardDeviceAdminReceiver`
  class honors exact description text documented in §2.2 texts.
- In-app strings for explanation / admin description / persistent indicator all come
  from one `strings-admin.xml` section; a CI test parses the XML and asserts the
  coupled phrases cohere.
- **Accept:** on a fresh guest emulator, cold-start → each grant comes from actually
  setting the OS toggle; a Device admin toggle-off on OS Settings produces an
  `admin_events` row visible to the parent app live; no three-strings mismatch;
  `assembleDebug` + `lintDebug` green; the manifest assertions include
  `PACKAGE_USAGE_STATS`, `BOOT_COMPLETED`, device-admin receiver.

### M2b — App inventory + instant block
- Child-side `PackageManager` scan uses the §3.1 filter (launcher-visible system apps
  included, framework providers not).
- `installed_apps` + `blocked_apps` tables live with §3.3 RLS, tested in
  `supabase/tests/rls_m2.sql` against the four-subject matrix (same mechanism as the M1
  RLS run through `run-rls-tests.mjs`).
- Parent toggles block ↔ unblock; the child's enforcement set updates via realtime
  on the returning device (or via sync on reconnection) — verified on-device with the
  same 'no interaction' test methodology as the M1 time_requests test.
- Empty/loading/error states on the app list; search-as-you-type over the inventory.
- **Accept:** package install/removal on the child device appears/disappears in parent
  list within one (5 s + realtime) cycle; a block that is set while the child keeps the
  phone in hand produces a block screen within 30 s; parent repo has no
  parent→child-config screen path to bypass; CI manifest assertions extended for the
  new receiver; no RLS gap (`anon`/cross-family writes rejected with 42501).

### M2c — Uninstall request
- Parent requests uninstall for a non-system app; the system-card flow in §5; result
  statuses surface back.
- Cancelled/failed/pending beyond 45 s have explicit UI states on both sides.
- No attempt to inherit install/uninstall dialogs — Play-manifest review check
  (`grep` of the APK for `ACTION_UNINSTALL_PACKAGE` with the deprecated `EXTRA_RETURN_RESULT`
  manifest-driven hook must be absent... true test = code-review of the flow).
- **Accept:** end-to-end on-device: request → child confirm → real uninstall → parent
  card shows "done"; cancelled → "declined"; a re-request respects the 5 min cooldown;
  `rls_m2.sql` covers no cross-child updates.

### M2d — Dashboard redesign
- Bottom nav 5 + More, our IA layout honored; screens rebuilt against Material 3
  tokens (no raw theme hard-codes in feature modules other than brand seed).
- Overview/App Rules/Requests all perform under a simple skeleton-state inversion;
  screenshot rounds (leaving `prefers-reduced-motion` honoured) are equal across runs.
- **Accept:** parent orchestrates "block YouTube while the child is using it" from
  Overview → App Rules without dropping into Settings; empty states show for a
  freshly-paired family (no inventory yet); skeleton loading for all new cards;
  regressions from M1 (PIN gate, sign-out→sign-in, Sync:live heartbeat) re-verified
  on-device with the same methodology as the M1 report.
- New module shape: no new Gradle modules are expected (device admin lands inside
  `:service:*` as a module if it must exist — either `:service:admin` (new, added to
  settings.gradle + graph tests) or folded under `:service:enforcement` — decision at
  build-prove time in M2a with the exact choice recorded here). CI is extended
  accordingly.

---

## 8. Explicitly out of scope (on the record)

As §0: no mirroring, no remote input, no accessibility interception of system dialogs,
no hiding/disguising the child app. Implementations that fail into these are a red-flag
boundary for the whole project. Device Owner (M4) is the sanctioned path for silent
uninstall, and it requires that the *child device itself* be provisioned with informed
consent at setup — noted here so it is never a surprise later.

---

*Awaiting approval. Reply APPROVED or give changes.*
