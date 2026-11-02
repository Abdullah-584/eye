# M2b — App Inventory + Instant Block (Phase 1 plan)

Status: **proposed**, no code written.
Parent doc: `docs/M2-PLAN.md` §3, §4, §7 M2b. This document supersedes those
sections where they conflict, and every divergence is called out in §8.

---

## 0. What already exists (do not rebuild)

| Concern | Where it lives today |
|---|---|
| Rule/limit realtime pipeline | `core/data/.../sync/SyncEngine.kt` — parent channel `family:<ids>`, child channel `child:<id>`, `version` LWW, deletes → debounced `deltaSync` |
| Enforcement decision | `core/domain/.../enforcement/EnforcementEngine.kt` — already accepts `blockedApps: Set<String>` and returns `BlockReason.APP_BLOCKED` |
| Enforcement loop | `service/enforcement/.../EnforcementService.kt` — 1 s tick, foreground service, pairs-gated, already observes Room for limits/schedules |
| Block overlay | `feature/enforcement/.../BlockScreen.kt` + `BlockActivity` — renders per `BlockReason` |
| Child identity | `core/data/.../mode/ChildDeviceStore.kt` — `childId` + `deviceId`, persisted |
| RLS helpers | `supabase/migrations/20260101000100_rls_helpers.sql` — `is_parent_of_child`, `is_device_of_child`, `my_device_id`, `is_parent_of_family` |
| Realtime publication | `supabase/migrations/20261028000000_realtime_publication.sql` — idempotent do-block, adds table + `replica identity full` |
| RLS test style | `supabase/tests/rls_admin_events.sql` — pgTAP, 4-subject matrix, `supabase test db` in CI |
| Local cache | `core/database` — Room, **version 2**, `fallbackToDestructiveMigration()` in `DataModule.kt` |
| Periodic work | `core/data/.../sync/UploadWorker.kt` — `enqueueUniquePeriodicWork` pattern |

**The one line M2b changes in the existing enforcement path**:
`EnforcementService.tickLoop()` currently passes `blockedApps = emptySet()`
(`EnforcementService.kt`, inside the `EnforcementEngine.evaluate(...)` call).
M2b replaces that literal with an observed set. There is **no second blocking
path** — inventory/block features feed this exact call.

---

## 1. Child-side installed-app query

### 1.1 Source of truth
`PackageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)`
— *not* `getInstalledPackages()`. Rationale: the parent-facing list is "apps a
child can open", and the launcher intent set is exactly that, already
deduplicated per package, and already package-visibility-legal under the
`<queries>` declaration below.

### 1.2 "User-relevant" filter

**Include** a package when all hold:
1. It resolves `ACTION_MAIN` + `CATEGORY_LAUNCHER` for the current user.
2. It is not in the hard-exclusion list (§1.3).

Consequences, stated explicitly:
- System apps **with** a launcher (YouTube, Calculator, Play Store, Chrome) are
  included — these are the ones a parent actually blocks.
- System apps **without** a launcher (providers, GMS background services, print
  spooler, `com.android.*.overlay`) are excluded — they are the 200-row noise
  dump M2-PLAN warned about.
- Non-launcher *user* apps (keyboards, widget-only apps, services) are excluded.
  Blocking a keyboard bricking text input is a worse failure than an app
  missing from the list; if that changes, it is an additive `launchable=false`
  row set, not a schema change.

### 1.3 Hard exclusions (filtered out, not merely unblockable)

| Package | Why |
|---|---|
| `com.familyguard.child` (own `applicationId`) | Self-block would brick the app |
| `com.android.settings` | M2a's coupling contract promises the child can reach Settings ▸ Apps ▸ Special access to deactivate admin. Blocking Settings would make that promise false |
| `com.android.systemui` | Blocks notifications/status bar; breaks the persistent "Parental controls active" indicator we committed to |
| default dialer (`TelecomManager.defaultDialerPackage`) | Emergency calling |
| default SMS (`Telephony.Sms.getDefaultSmsPackage`) | Emergency contact |
| `com.android.packageinstaller`, `com.google.android.packageinstaller` | Prevents the OS uninstall flow M2c depends on |

These are excluded from the **inventory entirely**, so they never appear as a
toggle that does nothing. Documented in code + string copy for M2c.

### 1.4 Captured fields
`package_name`, `app_name` (label, best-effort, truncated 120 chars),
`is_system`, `version_name`, `launchable` (always `true` in M2b; column exists
so the filter can widen without a migration), `icon_sha` (nullable).

### 1.5 Icons — decision
Store **`icon_sha`** (SHA-256, hex, of the launcher icon bitmap bytes) and
**no bytes anywhere**. Parent UI renders a deterministic monogram (first
grapheme of label, colour from `packageName.hashCode()`), per M2-PLAN §3.1's
M2 default. Icon transport (R2 upload + on-demand parent fetch) stays out of
M2b; the hash column makes a later cache-keyed fetch additive, not a rewrite.
Consequence: inventory payload is text-only, ~30 KB for 200 apps.

---

## 2. Sync mechanism

### 2.1 Manifest — package visibility (`<queries>`)

Android 11+ filters both `PackageManager` results **and** broadcast delivery to
packages you can see. Required in `app-child/src/main/AndroidManifest.xml`:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" /> <!-- intentionally absent -->
```

`QUERY_ALL_PACKAGES` is deliberately **not** requested: it triggers Play policy
review, and the launcher-intent query covers the product requirement. A CI
assertion enforces its absence (§6.3).

### 2.2 Change detection — three layers, because one does not work

**Correction to `docs/M2-PLAN.md` §3.1:** a manifest-declared receiver for
`ACTION_PACKAGE_ADDED` is *not* delivered on Android 8+ — `PACKAGE_ADDED` is
**not** on the implicit-broadcast exemption list (only
`ACTION_PACKAGE_FULLY_REMOVED` and `ACTION_PACKAGE_DATA_CLEARED` are).

Therefore:

1. **Live (primary): runtime-registered receiver inside `EnforcementService`.**
   That service is a foreground service, so its process is alive whenever the
   install is paired — a dynamic `registerReceiver` there receives
   `PACKAGE_ADDED`, `PACKAGE_REMOVED`, `PACKAGE_REPLACED` regardless of API
   level, and hands the event straight to the inventory repository.
   Unregistered in `onDestroy()` alongside the existing screen receiver.
2. **Cold-start-safe (backstop): manifest receiver for
   `ACTION_PACKAGE_FULLY_REMOVED`** (exempted broadcast) with
   `<data android:scheme="package" />` — the scheme is mandatory or the filter
   never matches. It marks the row `removed_at` and triggers a reconcile.
3. **Periodic reconcile: `InventoryWorker`** (`CoroutineWorker`, unique 6 h
   periodic, mirroring `UploadWorker.kt`) — full scan + diff. Also runs once on
   child app cold start and immediately after a successful pairing.

All three converge on one function: `InventoryDiff.reconcile(previous, scan)`,
which is pure and unit-tested (§6.1).

### 2.3 Schema — `public.installed_apps`

```sql
create table public.installed_apps (
  id            uuid primary key default gen_random_uuid(),
  child_id      uuid not null references public.children (id) on delete cascade,
  device_id     uuid not null references public.devices (id) on delete cascade,
  package_name  text not null check (char_length(package_name) between 1 and 200),
  app_name      text not null default '',
  is_system     boolean not null default false,
  version_name  text,
  launchable    boolean not null default true,
  icon_sha      text,
  first_seen    timestamptz not null default now(),
  last_seen     timestamptz not null default now(),
  removed_at    timestamptz,
  unique (device_id, package_name)
);

create index installed_apps_child_idx  on public.installed_apps (child_id);
create index installed_apps_device_idx on public.installed_apps (device_id);
create index installed_apps_live_idx   on public.installed_apps (device_id)
  where removed_at is null;
```

- **Uniqueness is `(device_id, package_name)`, not `(child_id, package_name)`**
  (divergence from M2-PLAN §3.2): one child can have two paired phones, and the
  parent UI is explicitly "per child device". `child_id` is kept for
  RLS/realtime filtering and denormalised reads.
- **Soft delete (`removed_at`)** rather than hard delete: uninstall/reinstall
  churn becomes one upsert, the parent can show "removed" history, and the
  child's delete permission stays narrow.
- `first_seen` is preserved on upsert (`do update set last_seen = …, removed_at = null`
  never touches `first_seen`).

### 2.4 Schema — `public.blocked_apps`

```sql
create table public.blocked_apps (
  child_id     uuid not null references public.children (id) on delete cascade,
  package_name text not null,
  blocked      boolean not null default true,
  version      integer not null default 1,
  updated_by   uuid not null default auth.uid() references public.profiles (id),
  updated_at   timestamptz not null default now(),
  primary key (child_id, package_name)
);

create index blocked_apps_child_idx on public.blocked_apps (child_id);

create or replace function public.touch_blocked_app()
returns trigger language plpgsql as $$
begin
  new.version := old.version + 1;
  new.updated_at := now();
  new.updated_by := coalesce(auth.uid(), old.updated_by);
  return new;
end $$;

create trigger blocked_apps_touch
  before update on public.blocked_apps
  for each row execute function public.touch_blocked_app();
```

**Why a separate table instead of `installed_apps.is_blocked`** (the user asked
for one and a justification):

Postgres RLS is row-level, not column-level. The device must be able to update
its own inventory rows (label/version/`last_seen` on reinstall). If block state
lived in that same row, the device's UPDATE policy would let a child app
`update installed_apps set is_blocked = false` — a one-line self-unblock.
Splitting gives the device **no write path at all** to block state, and gives
the parent **no write path** to inventory. The boundary is structural, not
conventional.

`version` + trigger make Realtime delivery ordering-safe: an out-of-order
`block` after a `unblock` is discarded by the same `version >= local.version`
rule `SyncEngine.applyLimit` already uses.

### 2.5 RLS (same milestone, non-negotiable)

`installed_apps`
```sql
alter table public.installed_apps enable row level security;

-- Parent: read own children's inventory. Child device: read its own.
create policy "installed_apps: parent or device reads"
  on public.installed_apps for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

-- Device writes only its own child + its own device row.
create policy "installed_apps: device reports"
  on public.installed_apps for insert
  with check (public.is_device_of_child(child_id) and device_id = public.my_device_id());

create policy "installed_apps: device updates"
  on public.installed_apps for update
  using (device_id = public.my_device_id() and public.is_device_of_child(child_id))
  with check (device_id = public.my_device_id() and public.is_device_of_child(child_id));

create policy "installed_apps: device cleans up"
  on public.installed_apps for delete
  using (device_id = public.my_device_id() and public.is_device_of_child(child_id));
-- No parent insert/update/delete policy: parent is read-only over inventory.
```

`blocked_apps`
```sql
alter table public.blocked_apps enable row level security;

create policy "blocked_apps: parent or device reads"
  on public.blocked_apps for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

create policy "blocked_apps: parent blocks"
  on public.blocked_apps for insert
  with check (public.is_parent_of_child(child_id));

create policy "blocked_apps: parent unblocks"
  on public.blocked_apps for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

create policy "blocked_apps: parent removes row"
  on public.blocked_apps for delete
  using (public.is_parent_of_child(child_id));
-- No device write policy: the child can never set or clear `blocked`.
```

### 2.6 Realtime
Extend the existing idempotent do-block pattern
(`20261028000000_realtime_publication.sql`) with `installed_apps` and
`blocked_apps` + `replica identity full` (required because filters use
`child_id`, not the PK — DELETE payloads must carry it).

`SyncEngine` additions:
- **Child channel** (`startChildSync`): watch `blocked_apps`
  INSERT/UPDATE/DELETE filtered `child_id=eq.<child>`; apply with version guard;
  DELETE → debounced `deltaSync` (existing behaviour).
- **Parent channel** (`startParentSync`): watch `installed_apps`
  INSERT/UPDATE/DELETE per child (new app appears in the open dashboard), plus
  `blocked_apps` INSERT/UPDATE so a second parent device sees toggles.
- `deltaSync(childId)` gains `appInventory.refresh` + `blockedApps.refresh`.

### 2.7 Room
- Bump `@Database(version = 3)`, add `InstalledAppEntity`, `BlockedAppEntity`
  (composite PK `(childId, packageName)`), DAOs.
- `fallbackToDestructiveMigration()` already exists (`DataModule.kt:38`) and the
  database comment records destructive wipe as the policy until a migration-test
  milestone lands — **no hand-written migration**; a wiped cache re-syncs from
  server. Noted so this is a deliberate choice, not an oversight.

---

## 3. Block / unblock

### 3.1 Write path
Parent toggle → `AppBlockRepository.setBlocked(childId, pkg, blocked)` →
Postgrest upsert on `(child_id, package_name)` with `blocked=…` (trigger bumps
`version`) → optimistic Room write for the parent's own UI.

### 3.2 Read path into enforcement (reuse, no second path)
`EnforcementService` gains `@Volatile var blockedApps: Set<String>` fed by
`appBlocks.observe(childId).collect { blockedApps = it.filter { b -> b.blocked }
  .map { it.packageName }.toSet() }`, and the `evaluate(...)` call passes it
instead of `emptySet()`. Room observation → tick (≤1 s) → `BlockActivity`.
No new service, no polling shortcut, no parallel block list.

### 3.3 Immediacy
Parent write → Realtime push (<1 s typical) → child Room → next 1 s tick →
overlay. Target ≤30 s total (the milestone acceptance bound), realistically
2–4 s. If the blocked app is currently foreground, the overlay appears on the
next tick; we do **not** kill processes (unchanged M1 rule).

Unblock while the overlay is showing: the observed set drops the package, the
next tick returns `BlockReason.NONE`, `blockingPackage` clears and the overlay
is dismissed (`BlockActivity` already finishes on `onBackPressed`/timeout and
is re-evaluated; M2b adds no new teardown mechanism).

### 3.4 Offline behaviour (explicit)
- **Parent offline:** toggle fails at the network call; UI reverts and shows an
  inline error. No queued write — silently deferring a security-relevant toggle
  is worse than telling the parent it did not apply.
- **Child offline:** parent's toggle succeeds server-side. The child catches up
  by (a) Realtime re-subscribe → channel join replays nothing, so (b) the
  existing on-reconnect/on-start `deltaSync` pulls `blocked_apps`, and (c) the
  60 s maintenance loop in `EnforcementService` gains a
  `appBlocks.refresh(childId)` call as the belt-and-braces path.
  Worst case with a live socket is <2 s; worst case after reconnect is one
  maintenance cycle (≤60 s) — same bound M2-PLAN recorded for admin alerts.

---

## 4. Parent UI (M2b scope only — not the M2d redesign)

New module `feature:apps` (consistent with the repo's per-surface modules;
`feature:rules` stays focused on limit editors):

- `InstalledAppsScreen(state, onQueryChange, onToggle, onRefresh, onBack)`
  - item: monogram, label, package name (small), `Switch` with
    `contentDescription = "Block <App> for <Child> — currently <state>"`,
    "Blocked" chip when on, `is_system` badge.
  - search: text field, case-insensitive `contains` on label + package, applied
    via `combine(inventory, query)` in the ViewModel (client-side; the list is
    ≤250 rows, no server round-trip).
  - states: loading skeleton rows; empty ("No apps reported yet — is the child
    device online?"); error card + Retry; "Last sync <relative>" from
    `max(last_seen)`.
  - no uninstall affordance (that is M2c) — a disabled row action placeholder is
    **not** added; scope stays clean.
- `InstalledAppsViewModel`: `childId` in, `combine(inventory, blocked, query)`.
- Entry point for M2b: a card/button on `FamilyDashboardScreen` →
  `Routes.APPS` in `app-parent/.../FamilyGuardRoot.kt` (the tab bar is M2d).

Accessibility: 48 dp targets, switch state in semantics, no colour-only state.

---

## 5. Milestones

### M2b-1 — Schema, RLS, realtime (SQL only)
- `supabase/migrations/20261101000000_installed_apps.sql` — both tables, trigger,
  indexes, RLS, publication extension.
- `supabase/tests/rls_m2.sql` — pgTAP, 4-subject matrix.
- **Accept:** `supabase db reset` applies clean from scratch; `supabase test db`
  green including the new suite; anon and cross-family writes rejected with
  `42501`; `blocked_apps` device INSERT/UPDATE rejected; `installed_apps` parent
  INSERT/UPDATE/DELETE rejected; publication contains both tables with
  `replica identity full`.

### M2b-2 — Child inventory + sync
- `core/domain`: `InstalledApp`, `AppVisibility` (pure filter),
  `InventoryDiff` (pure reconcile), `AppInventoryRepository` + `AppBlockRepository`
  interfaces.
- `core/data`: scanner (ApplicationContext + `PackageManager`), repos, DTOs,
  mappers, `InventoryWorker`, `SyncEngine` watches, `deltaSync` extension.
- `core/database`: entities, DAOs, v3.
- `service/enforcement`: dynamic package receiver in `EnforcementService`,
  manifest `PACKAGE_FULLY_REMOVED` receiver, `blockedApps` wiring,
  maintenance-loop refresh.
- `app-child/AndroidManifest.xml`: `<queries>`.
- **Accept:** paired device reports a filtered inventory (system launcher apps
  in, providers out, hard-exclusions absent); install/remove of a launcher app
  reflected in `installed_apps` within 5 s while the service runs; cold start
  reconciles; unit tests §6.1 green; `assembleDebug` + `lintDebug` green.

### M2b-3 — Parent UI + instant toggle
- `feature:apps` (screen + VM), `Routes.APPS` wiring, `feature:apps` in
  `settings.gradle.kts` / parent app deps.
- Parent-side Room read of `installed_apps` + `blocked_apps` (already synced in
  M2b-2), optimistic toggle.
- **Accept:** on-device checklist §6.4 fully executed, including the two
  "live, no restart" items; block takes effect on the child within 30 s;
  unblock restores use; second child device not affected.

---

## 6. Test plan

### 6.1 Pure JVM unit tests (no Android, no Robolectric)
| Test | Asserts |
|---|---|
| `AppVisibilityTest` | launcher system app included; non-launcher system app excluded; Settings/SystemUI/dialer/SMS/package-installer/own package excluded; non-launcher user app excluded |
| `InventoryDiffTest` | new package → insert; missing package → `removed_at`; reappearing package → `removed_at` cleared, `first_seen` unchanged; label/version change → update only |
| `BlockedVersioningTest` | out-of-order lower `version` does not overwrite; equal version applied; DELETE triggers reconcile |
| `InstalledAppsQueryTest` (VM) | search filters on label and package, case-insensitive; empty and blocked-only states |

### 6.2 pgTAP (`supabase/tests/rls_m2.sql`)
Four subjects (parent A of the child, parent of another family, paired device
user, anon) × two tables: read/write matrix as in §2.5, plus
`version`-bump-on-update trigger and `updated_by` default. Runs in CI via
`supabase test db`.

### 6.3 CI manifest/contract assertions (extend the M2a pattern)
A pure JVM test (same shape as `AdminStringCouplingTest`) reading source files:
- `app-child/.../AndroidManifest.xml` contains `<queries>` with
  `ACTION_MAIN` + `CATEGORY_LAUNCHER`.
- `QUERY_ALL_PACKAGES` is **absent** from every manifest.
- `PACKAGE_FULLY_REMOVED` receiver exists **with** `<data android:scheme="package"/>`.
- No manifest receiver claims `ACTION_PACKAGE_ADDED` (it would be dead code —
  encode the §2.2 correction so nobody re-adds it).
- `EnforcementService` no longer passes `blockedApps = emptySet()` (a grep
  assertion against the literal, so the wiring cannot silently regress).

### 6.4 On-device checklist (required before closing any M2b sub-milestone that
touches runtime behaviour — per the M2a lesson, not optional)

1. Pair child install (A) to parent app (P) on the same emulator/network.
2. Open App Rules; inventory lists launcher apps; Settings/dialer absent.
3. **With the parent dashboard open**, install a launcher app on the child
   (`adb install` a tiny APK): it appears in P **without restarting either app**.
4. Uninstall it: row disappears (or shows "removed") **without restart**.
5. Toggle block on that app; open it on the child device: `BlockActivity`
   appears **within 30 s, without moving the child device to another screen**.
6. Toggle unblock; reopen the app: it launches immediately.
7. Put the child in airplane mode, toggle block on the parent, restore network:
   block applies within one maintenance cycle (≤60 s), no restart.
8. Second child device (B) paired to the same family: blocking an app on A does
   not affect B.
9. `adb shell cmd device_config`-free sanity: no `service_role` in logs; RLS
   rejects a forged child-session block write (checked once with curl +
   device JWT).

---

## 7. Performance / resource constraints
- Inventory payload: text-only, ≤250 rows, single PostgREST upsert with
  `on_conflict=device_id,package_name` (batched, one request per reconcile).
- Scan cost: `queryIntentActivities` on a 200-app device is ~10–20 ms; runs on
  `Dispatchers.IO`, 6 h periodic + event-driven only.
- No icon bytes, no R2 dependency, no polling loop tighter than the existing 1 s
  enforcement tick.

---

## 8. Explicit divergences from `docs/M2-PLAN.md`
1. `installed_apps` uniqueness is `(device_id, package_name)` and gains
   `device_id` — inventory is per device (§2.3).
2. `ACTION_PACKAGE_ADDED` cannot be a manifest receiver; live detection moves
   to a runtime receiver in the foreground service, with
   `PACKAGE_FULLY_REMOVED` as the only manifest receiver (§2.2).
3. Hard-exclusion safety list added: Settings, SystemUI, dialer, SMS, package
   installer, own package (§1.3) — required to keep M2a's honesty contract true.
4. `blocked_apps` gains `version` + touch trigger for ordered realtime
   application (§2.4).
5. Soft delete (`removed_at`) replaces M2-PLAN's contradictory
   "delete rows, then add `removed_at`" (§2.3).
6. Room bumps to v3 with the existing destructive-fallback policy — no
   hand-written migration (§2.7).

Out of scope, unchanged: no screen mirroring, no remote input, no accessibility
service, no process killing, no auto-granted permissions, no uninstall flow
(M2c).

---

Awaiting approval. Reply APPROVED or give changes.
