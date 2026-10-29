# FamilyGuard (eye)

An Android parental-control app: a parent pairs a child's device, then sets app
time limits, bed-time schedules, and screen-time rules, and can see the child's
last known location. Enforcement runs on-device; state syncs through Supabase.

## Modules

| Module | Responsibility |
| --- | --- |
| `app` | Compose navigation root, Hilt application, activity |
| `core/common` | `AppResult` / `AppError` result types |
| `core/domain` | Pure models and evaluators — `ScheduleEvaluator`, `LimitEvaluator`, `EnforcementEngine` (no Android, no wall clock: every function takes `now` + zone) |
| `core/database` | Room entities and DAOs |
| `core/data` | Repositories, DTOs/mappers, `SyncEngine`, `AppModeStore` |
| `core/network` | Supabase client, session persistence |
| `core/auth` | Supabase-backed `AuthRepository` |
| `core/permissions` | Usage-access / overlay / location permission checks |
| `core/ui` | Shared Compose components |
| `feature/*` | `onboarding`, `pairing`, `family`, `childhub`, `rules`, `reports`, `requests`, `settings`, `enforcement`, `location` |
| `service/*` | Foreground services: `enforcement` (block screen) and `location` (queued fixes) |
| `supabase` | SQL migrations, RLS policies, edge functions (`pairing-create`, `pairing-verify`, `pairing-revoke`, `device-heartbeat`, `time-request-decide`) |

## Backend

Migrations live in `supabase/migrations/` and are applied in filename order;
RLS is enabled per-table with helper functions in `rls_helpers.sql`. Edge
functions run on Deno and are invoked by the app with the user's JWT.

## Build

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint
```

Requires a JDK 17 toolchain and the Android SDK (`sdk.dir` in
`local.properties`, which is git-ignored).

## Tests

`core:domain` holds the behavior-critical unit tests for schedule and limit
evaluation; `core:auth`, `core:network` and `feature:settings` cover input
validation, session persistence and the PIN gate. Tests pin time explicitly
rather than reading the clock, so they are deterministic.
