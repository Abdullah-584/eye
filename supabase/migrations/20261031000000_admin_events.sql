-- ============================================================================
-- 20261031000000_admin_events.sql — M2a: device-admin state alerts
--
-- The child app cannot stop itself from being deactivated: any child can turn
-- the Device Admin off in Settings > Apps > Special access > Device admin
-- apps (PLAN M2-§2.3 — it is the OS-sanctioned path and we must never fight
-- it with an AccessibilityService). What we CAN do is *observe and report*:
-- when the enforcement loop sees the admin flip off, it records an
-- `admin_events` row and postgres_changes carries it to the parent inbox
-- live. `onDisableRequested`'s dialog is a warning only; the server-truth row
-- is written by the app that can still run, never by a hook.
--
-- Append-only for clients: no DELETE policy, and the only UPDATE policy is
-- the parent's transparent acknowledgement (`parent_ack_at`). Neither side
-- can forge an event: a child session may only create rows for its own
-- child+device pair, and a parent may not create rows at all.
-- ============================================================================

create table public.admin_events (
  id            uuid primary key default gen_random_uuid(),
  child_id      uuid not null references public.children (id) on delete cascade,
  device_id     uuid not null references public.devices (id) on delete cascade,
  -- admin_disabled | admin_reenabled
  event_type    text not null check (event_type in ('admin_disabled', 'admin_reenabled')),
  occurred_at   timestamptz not null default now(),
  parent_ack_at timestamptz,
  created_at    timestamptz not null default now()
);

create index admin_events_child_idx on public.admin_events (child_id);
create index admin_events_pending_idx on public.admin_events (child_id)
  where parent_ack_at is null;

alter table public.admin_events enable row level security;

-- The paired device reports its own admin state. event_type is constrained by
-- CHECK above; the child_id/device_id pair by the existing M1 helpers.
create policy "admin_events: device reports own state"
  on public.admin_events for insert
  with check (
    public.is_authenticated_user()
    and child_id = public.my_child_id()
    and device_id = public.my_device_id()
  );

create policy "admin_events: parents and device read"
  on public.admin_events for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

-- Acknowledgement only: the parent flips parent_ack_at on their own family's
-- rows. RLS rows are already filtered by the USING clause, so a cross-family
-- update is filtered out rather than rejected — same shape as the M1
-- time_requests policy.
create policy "admin_events: parent acknowledges"
  on public.admin_events for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

-- No DELETE policy, and no parent INSERT policy: events are created by the
-- device and removed only by the retention job (service_role, BYPASSRLS).

-- Realtime: parents get the alert in the same `postgres_changes` fast path
-- the M1 rule pipeline uses (REPLICA IDENTITY FULL because the client filters
-- on child_id, which is not the primary key). Idempotent, like 20261028.
do $$
begin
  -- Create the publication when this runs outside Supabase (CI pgTAP env is
  -- Supabase-shaped; the vanilla-Postgres harness benefits from this guard).
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
  if not exists (
    select 1
      from pg_publication_tables
     where pubname = 'supabase_realtime'
       and schemaname = 'public'
       and tablename = 'admin_events'
  ) then
    alter publication supabase_realtime add table public.admin_events;
  end if;
end $$;

alter table public.admin_events replica identity full;
