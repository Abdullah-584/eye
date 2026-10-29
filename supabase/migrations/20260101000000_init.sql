-- ============================================================================
-- 20260101000000_init.sql — FamilyGuard core schema
--
-- Tables, constraints, indexes and triggers only. RLS lives in
-- 20260101000100_rls_helpers.sql and 20260101000200_rls_policies.sql.
--
-- Rules for this project:
--   * EVERY table created here is enabled for RLS (in the next migration) and
--     every table is listed in supabase/tests/rls_matrix.sql.
--   * No extensions are required: gen_random_uuid() is core Postgres 13+,
--     and Supabase runs Postgres 15+. This keeps the migration runnable on
--     any vanilla Postgres (used by the local verification harness).
--   * No secrets, no service_role assumptions in SQL: service_role bypasses
--     RLS at the role level, we never rely on it for correctness.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- PROFILES — 1:1 with auth.users. Created automatically on sign-up.
-- ---------------------------------------------------------------------------
create table public.profiles (
  id            uuid primary key references auth.users (id) on delete cascade,
  display_name  text not null check (char_length(display_name) between 1 and 60),
  avatar_url    text,
  locale        text not null default 'en',
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- FAMILIES — the ownership root. Everything a parent can see hangs off this.
-- ---------------------------------------------------------------------------
create table public.families (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 60),
  created_by  uuid not null references public.profiles (id) on delete restrict,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- FAMILY MEMBERS — profile <-> family with a role. Extra table beyond the
-- brief's list; without it "multi-parent" (default in PLAN.md Q3) has no home.
-- It is RLS-enabled in the next migration like every other table here.
-- ---------------------------------------------------------------------------
create table public.family_members (
  family_id   uuid not null references public.families (id) on delete cascade,
  profile_id  uuid not null references public.profiles (id) on delete cascade,
  role        text not null check (role in ('parent', 'guardian')),
  added_by    uuid references public.profiles (id),
  created_at  timestamptz not null default now(),
  primary key (family_id, profile_id)
);

-- ---------------------------------------------------------------------------
-- CHILDREN — one row per child. Rules/usage/location all reference this.
-- ---------------------------------------------------------------------------
create table public.children (
  id           uuid primary key default gen_random_uuid(),
  family_id    uuid not null references public.families (id) on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 60),
  birth_date   date,
  avatar_url   text,
  timezone     text not null default 'UTC',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- DEVICES — a paired child phone. user_id is the Supabase auth user the device
-- signs in as; it is created by the pairing-verify Edge Function (M3), never by
-- a client. INSERT therefore has no client policy (deny by default).
-- ---------------------------------------------------------------------------
create table public.devices (
  id           uuid primary key default gen_random_uuid(),
  child_id     uuid not null references public.children (id) on delete cascade,
  user_id      uuid unique references auth.users (id) on delete cascade,
  device_name  text not null,
  model        text,
  android_sdk  integer,
  app_version  text,
  fcm_token    text,
  status       text not null default 'pending'
               check (status in ('pending', 'active', 'revoked')),
  paired_at    timestamptz,
  last_seen_at timestamptz,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- PAIRING CODES — hash only, written and read exclusively by Edge Functions.
-- ---------------------------------------------------------------------------
create table public.pairing_codes (
  id         uuid primary key default gen_random_uuid(),
  family_id  uuid not null references public.families (id) on delete cascade,
  child_id   uuid references public.children (id) on delete cascade,
  code_hash  text not null,
  code_hint  text not null default '******'
             check (char_length(code_hint) <= 6),
  created_by uuid not null references public.profiles (id) on delete cascade,
  attempts   integer not null default 0 check (attempts >= 0),
  expires_at timestamptz not null,
  used_at    timestamptz,
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- RULES — generic, versioned rule container.
-- ---------------------------------------------------------------------------
create table public.rules (
  id         uuid primary key default gen_random_uuid(),
  child_id   uuid not null references public.children (id) on delete cascade,
  kind       text not null check (kind in ('app_block', 'daily_total', 'block_on_schedule')),
  enabled    boolean not null default true,
  config     jsonb not null default '{}'::jsonb,
  starts_at  timestamptz,
  ends_at    timestamptz,
  version    integer not null default 1 check (version >= 1),
  updated_by uuid references public.profiles (id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- APP LIMITS — daily seconds per package.
-- ---------------------------------------------------------------------------
create table public.app_limits (
  id                  uuid primary key default gen_random_uuid(),
  child_id            uuid not null references public.children (id) on delete cascade,
  package_name        text not null check (package_name = btrim(package_name)),
  daily_limit_seconds integer not null
                      check (daily_limit_seconds between 60 and 86400),
  enabled             boolean not null default true,
  version             integer not null default 1 check (version >= 1),
  updated_by          uuid references public.profiles (id),
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (child_id, package_name)
);

-- ---------------------------------------------------------------------------
-- SCHEDULES — bedtime / school windows in the child's local time.
-- ---------------------------------------------------------------------------
create table public.schedules (
  id         uuid primary key default gen_random_uuid(),
  child_id   uuid not null references public.children (id) on delete cascade,
  name       text not null check (char_length(name) between 1 and 60),
  days_mask  smallint not null default 127 check (days_mask between 0 and 127),
  start_time time not null,
  end_time   time not null,
  action     text not null default 'lock' check (action in ('lock', 'warn')),
  enabled    boolean not null default true,
  version    integer not null default 1 check (version >= 1),
  updated_by uuid references public.profiles (id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (start_time <> end_time)
);

-- ---------------------------------------------------------------------------
-- WEB FILTERS — block/allow lists resolved locally by the VpnService (M6).
-- ---------------------------------------------------------------------------
create table public.web_filters (
  id         uuid primary key default gen_random_uuid(),
  child_id   uuid not null references public.children (id) on delete cascade,
  mode       text not null default 'blocklist' check (mode in ('blocklist', 'allowlist')),
  domains    text[] not null default '{}',
  categories text[] not null default '{}',
  resolver   text not null default 'safe_dns'
             check (resolver in ('safe_dns', 'local_blocklist')),
  enabled    boolean not null default true,
  version    integer not null default 1 check (version >= 1),
  updated_by uuid references public.profiles (id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- USAGE EVENTS — append-only facts, batched upload, 90-day retention.
-- The unique key doubles as the batch-upload idempotency key.
-- ---------------------------------------------------------------------------
create table public.usage_events (
  id           bigint generated always as identity primary key,
  child_id     uuid not null references public.children (id) on delete cascade,
  device_id    uuid not null references public.devices (id) on delete cascade,
  package_name text not null,
  event_type   text not null check (event_type in ('app_switch', 'screen_on', 'screen_off')),
  started_at   timestamptz not null,
  duration_sec integer not null check (duration_sec between 0 and 86400),
  created_at   timestamptz not null default now(),
  unique (device_id, package_name, started_at, event_type)
);

-- ---------------------------------------------------------------------------
-- USAGE DAILY — rollup for charts. Extra table beyond the brief's list;
-- RLS-enabled in the next migration.
-- ---------------------------------------------------------------------------
create table public.usage_daily (
  child_id     uuid not null references public.children (id) on delete cascade,
  package_name text not null,
  day          date not null,
  seconds      bigint not null default 0 check (seconds >= 0),
  pickups      integer not null default 0 check (pickups >= 0),
  updated_at   timestamptz not null default now(),
  primary key (child_id, package_name, day)
);

-- ---------------------------------------------------------------------------
-- LOCATIONS — 30-day retention, child-visible indicator backed by this.
-- ---------------------------------------------------------------------------
create table public.locations (
  id          bigint generated always as identity primary key,
  child_id    uuid not null references public.children (id) on delete cascade,
  device_id   uuid not null references public.devices (id) on delete cascade,
  lat         double precision not null check (lat between -90 and 90),
  lng         double precision not null check (lng between -180 and 180),
  accuracy_m  real check (accuracy_m is null or accuracy_m >= 0),
  battery_pct smallint check (battery_pct is null or battery_pct between 0 and 100),
  recorded_at timestamptz not null,
  created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- TIME REQUESTS — child asks for more time, parent decides via Edge Function.
-- ---------------------------------------------------------------------------
create table public.time_requests (
  id                uuid primary key default gen_random_uuid(),
  child_id          uuid not null references public.children (id) on delete cascade,
  device_id         uuid not null references public.devices (id) on delete cascade,
  package_name      text,
  requested_seconds integer not null
                    check (requested_seconds between 60 and 14400),
  reason            text check (reason is null or char_length(reason) <= 200),
  status            text not null default 'pending'
                    check (status in ('pending', 'approved', 'denied', 'expired')),
  granted_seconds   integer check (granted_seconds is null or granted_seconds >= 0),
  decided_by        uuid references public.profiles (id),
  decided_at        timestamptz,
  created_at        timestamptz not null default now(),
  check (status = 'pending' or status = 'expired' or decided_at is not null)
);

-- ---------------------------------------------------------------------------
-- AUDIT LOG — append-only. Consent records, rule edits, revocations.
-- ---------------------------------------------------------------------------
create table public.audit_log (
  id          bigint generated always as identity primary key,
  family_id   uuid not null references public.families (id) on delete cascade,
  actor_id    uuid,
  actor_type  text not null check (actor_type in ('parent', 'device', 'system')),
  action      text not null,
  target      text,
  metadata    jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- INDEXES (see PLAN.md §4.6 for the reasoning behind each)
-- ---------------------------------------------------------------------------
create index children_family_idx            on public.children (family_id);
create index family_members_profile_idx     on public.family_members (profile_id);
create index devices_child_idx              on public.devices (child_id);
create index devices_user_idx               on public.devices (user_id);
create index devices_fcm_idx                on public.devices (fcm_token)
       where fcm_token is not null;
create index pairing_codes_hash_idx         on public.pairing_codes (code_hash);
create index pairing_codes_expires_idx      on public.pairing_codes (expires_at);
create index rules_child_idx                on public.rules (child_id, updated_at desc);
create index app_limits_child_idx           on public.app_limits (child_id);
create index schedules_child_idx            on public.schedules (child_id);
create index web_filters_child_idx          on public.web_filters (child_id);
create index usage_events_child_time_idx    on public.usage_events (child_id, started_at desc);
-- NOTE: no expression index on date_trunc('day', started_at) — date_trunc over
-- a timestamptz is STABLE, and Postgres rejects non-IMMUTABLE functions in
-- index expressions. Range queries are served by the index above, and the
-- per-day report path reads usage_daily (see PLAN.md §4.6).
create index usage_daily_child_day_idx      on public.usage_daily (child_id, day desc);
create index locations_child_time_idx       on public.locations (child_id, recorded_at desc);
create index locations_retention_idx        on public.locations (recorded_at);
create index time_requests_child_idx        on public.time_requests (child_id, created_at desc);
create index time_requests_pending_idx      on public.time_requests (created_at desc)
       where status = 'pending';
create index audit_log_family_idx           on public.audit_log (family_id, created_at desc);

-- ---------------------------------------------------------------------------
-- updated_at maintenance
-- ---------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

do $$
declare
  t text;
begin
  foreach t in array array[
    'profiles', 'families', 'children', 'devices', 'rules',
    'app_limits', 'schedules', 'web_filters', 'usage_daily'
  ] loop
    execute format(
      'create trigger touch_%I before update on public.%I
         for each row execute function public.touch_updated_at()',
      t, t
    );
  end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- Profile auto-creation: every auth.users row gets a profile.
-- SECURITY DEFINER so it works regardless of RLS on public.profiles.
-- The sign-up request may carry a display name in raw_user_meta_data.
-- ---------------------------------------------------------------------------
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_name text;
begin
  v_name := coalesce(
    nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
    nullif(split_part(coalesce(new.email, ''), '@', 1), ''),
    'Parent'
  );

  insert into public.profiles (id, display_name)
  values (new.id, left(v_name, 60))
  on conflict (id) do nothing;

  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- Default family name hint is not created automatically: a parent must give
-- consent first (feature: M2, screen P4). Nothing is provisioned behind the
-- user's back.
