-- ============================================================================
-- 20261101000000_installed_apps.sql — M2b: app inventory + instant block
--
-- Two tables, deliberately separate (docs/M2b-PLAN.md §2.4):
--
--   * `installed_apps`  — what the paired device reports about the apps it
--     can launch. The device writes it; the parent is READ-ONLY over it.
--   * `blocked_apps`    — the parent's intent to block a package. The parent
--     writes it; the device is READ-ONLY over it.
--
-- Why not one table with an `is_blocked` column: Postgres RLS is row-level,
-- not column-level. The device must UPDATE its own inventory rows (label,
-- version, `last_seen`, reinstall tombstone clearing). If block state lived
-- in the same row, that UPDATE policy would let a child session run
-- `update installed_apps set is_blocked = false` — a one-line self-unblock.
-- Splitting the tables makes the boundary structural: the device has NO
-- write path to block state, the parent has NO write path to inventory.
--
-- `version` on blocked_apps + a touch trigger make realtime delivery
-- ordering-safe: an out-of-order `block` arriving after an `unblock` is
-- discarded by the same `version >= local.version` rule SyncEngine.applyLimit
-- already uses for limits (docs/M2b-PLAN.md §2.4).
--
-- Uniqueness is (device_id, package_name), NOT (child_id, package_name)
-- (divergence from M2-PLAN §3.2, recorded in M2b-PLAN §8.1): one child can
-- have two paired phones and the parent UI is per-device. `child_id` is kept
-- denormalised for RLS + realtime filtering.
--
-- Soft delete (`removed_at`) instead of row deletion (M2b-PLAN §8.5):
-- uninstall/reinstall churn collapses to one upsert, the parent can show
-- "removed" history, and the device's DELETE policy stays narrow.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- installed_apps — device-owned inventory, parent read-only.
-- ---------------------------------------------------------------------------
create table public.installed_apps (
  id            uuid primary key default gen_random_uuid(),
  child_id      uuid not null references public.children (id) on delete cascade,
  device_id     uuid not null references public.devices (id) on delete cascade,
  package_name  text not null check (char_length(package_name) between 1 and 200),
  app_name      text not null default '',
  is_system     boolean not null default false,
  version_name  text,
  -- Always true in M2b (the scanner only sees launcher-visible apps); the
  -- column exists so the filter can widen later without a migration.
  launchable    boolean not null default true,
  -- SHA-256 hex of the launcher icon bitmap; NEVER the bytes. Parent UI
  -- renders a deterministic monogram until an icon transport exists
  -- (M2b-PLAN §1.5).
  icon_sha      text,
  first_seen    timestamptz not null default now(),
  last_seen     timestamptz not null default now(),
  removed_at    timestamptz,
  unique (device_id, package_name)
);

create index installed_apps_child_idx  on public.installed_apps (child_id);
create index installed_apps_device_idx on public.installed_apps (device_id);
-- The live set the parent list and the reconcile diff read most often.
create index installed_apps_live_idx   on public.installed_apps (device_id)
  where removed_at is null;

alter table public.installed_apps enable row level security;

-- Parent reads its own children's inventory; the paired device reads its own
-- (the enforcement side never needs another device's rows, but the helper
-- keeps the policy one line and consistent with the rest of the schema).
create policy "installed_apps: parent or device reads"
  on public.installed_apps for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

-- The device reports ONLY its own child+device pair. `device_id =
-- my_device_id()` alone would already pin the row to the writing device; the
-- child_id check stops a device user that somehow knows another child's id
-- from attaching rows to it.
create policy "installed_apps: device reports"
  on public.installed_apps for insert
  with check (
    public.is_authenticated_user()
    and device_id = public.my_device_id()
    and public.is_device_of_child(child_id)
  );

-- The device may only touch rows it owns: label/version refresh, last_seen
-- heartbeat, and clearing removed_at on reinstall. Block state is not in
-- this table, so there is nothing here for a child session to self-unblock.
create policy "installed_apps: device updates own rows"
  on public.installed_apps for update
  using (device_id = public.my_device_id() and public.is_device_of_child(child_id))
  with check (device_id = public.my_device_id() and public.is_device_of_child(child_id));

create policy "installed_apps: device cleans up own rows"
  on public.installed_apps for delete
  using (device_id = public.my_device_id() and public.is_device_of_child(child_id));

-- No parent INSERT/UPDATE/DELETE policy: the parent is read-only over
-- inventory. A parent session writing here is filtered out silently (0 rows),
-- which the pgTAP suite asserts.

-- ---------------------------------------------------------------------------
-- blocked_apps — parent-owned intent, device read-only.
--
-- The PK is (child_id, package_name): blocks are per child (the enforcement
-- set is the child's), while inventory is per device. A second paired device
-- for the same child therefore shares one block set — the acceptance
-- criterion "blocking on A does not affect child B" holds because B is a
-- different child_id.
-- ---------------------------------------------------------------------------
create table public.blocked_apps (
  child_id     uuid not null references public.children (id) on delete cascade,
  package_name text not null check (char_length(package_name) between 1 and 200),
  blocked      boolean not null default true,
  -- Realtime ordering guard, bumped by the touch trigger below.
  version      integer not null default 1,
  updated_by   uuid not null default auth.uid() references public.profiles (id),
  updated_at   timestamptz not null default now(),
  primary key (child_id, package_name)
);

create index blocked_apps_child_idx on public.blocked_apps (child_id);

-- Bump version on every UPDATE so an out-of-order realtime UPDATE (an old
-- `block` landing after a newer `unblock`) is discarded client-side by the
-- same version guard SyncEngine already applies to limits. `updated_by`
-- falls back to the previous writer when the session has no uid (e.g. a
-- migration or service_role run), so the column never goes NULL despite its
-- NOT NULL.
create or replace function public.touch_blocked_app()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
  new.version    := old.version + 1;
  new.updated_at := now();
  new.updated_by := coalesce(auth.uid(), old.updated_by);
  return new;
end $$;

create trigger blocked_apps_touch
  before update on public.blocked_apps
  for each row execute function public.touch_blocked_app();

alter table public.blocked_apps enable row level security;

create policy "blocked_apps: parent or device reads"
  on public.blocked_apps for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

-- Only a parent of this child may create a block. The child session has no
-- INSERT policy at all — the structural half of "child cannot self-unblock".
create policy "blocked_apps: parent blocks"
  on public.blocked_apps for insert
  with check (public.is_authenticated_user() and public.is_parent_of_child(child_id));

create policy "blocked_apps: parent unblocks"
  on public.blocked_apps for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_authenticated_user() and public.is_parent_of_child(child_id));

create policy "blocked_apps: parent removes row"
  on public.blocked_apps for delete
  using (public.is_parent_of_child(child_id));

-- No device INSERT/UPDATE/DELETE policy: the child can read the block set
-- (the enforcement loop needs it) but can never set or clear `blocked`.

-- ---------------------------------------------------------------------------
-- Realtime: both tables join the same `supabase_realtime` publication the M1
-- pipeline uses. REPLICA IDENTITY FULL is required because clients filter on
-- `child_id`, which is not the PK of either table — without it UPDATE/DELETE
-- payloads omit child_id and the filter drops them silently. Idempotent, the
-- same guard shape as 20261028 and 20261031.
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;

  foreach t in array array['installed_apps', 'blocked_apps'] loop
    if not exists (
      select 1
        from pg_publication_tables
       where pubname = 'supabase_realtime'
         and schemaname = 'public'
         and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;

    execute format('alter table public.%I replica identity full', t);
  end loop;
end $$;
