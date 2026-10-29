-- ============================================================================
-- 20260101000200_rls_policies.sql — Row Level Security for every table
--
-- Deny by default: enable RLS first, then add narrow policies. A table with
-- RLS enabled and no policy rejects every operation for normal roles.
--
-- Every policy below references ONLY:
--   * auth.uid()                       (null for anon / service_role)
--   * the row's own columns
--   * the SECURITY DEFINER helpers from 20260101000100_rls_helpers.sql
-- It never subqueries another RLS-protected table inline, which is what
-- causes "infinite recursion detected in policy for relation X".
--
-- service_role bypasses RLS at the role level (Supabase grants BYPASSRLS),
-- so Edge Functions can do pairing, decisions and retention without needing
-- permissive client policies.
-- ============================================================================

alter table public.profiles       enable row level security;
alter table public.families       enable row level security;
alter table public.family_members enable row level security;
alter table public.children       enable row level security;
alter table public.devices        enable row level security;
alter table public.pairing_codes  enable row level security;
alter table public.rules          enable row level security;
alter table public.app_limits     enable row level security;
alter table public.schedules      enable row level security;
alter table public.web_filters    enable row level security;
alter table public.usage_events   enable row level security;
alter table public.usage_daily    enable row level security;
alter table public.locations      enable row level security;
alter table public.time_requests  enable row level security;
alter table public.audit_log      enable row level security;

-- ---------------------------------------------------------------------------
-- profiles
-- ---------------------------------------------------------------------------
create policy "profiles: read own and family"
  on public.profiles for select
  using (id = auth.uid() or public.shares_family_with(id));

create policy "profiles: insert own"
  on public.profiles for insert
  with check (id = auth.uid());

create policy "profiles: update own"
  on public.profiles for update
  using (id = auth.uid())
  with check (id = auth.uid());

create policy "profiles: delete own"
  on public.profiles for delete
  using (id = auth.uid());

-- ---------------------------------------------------------------------------
-- families
-- ---------------------------------------------------------------------------
create policy "families: members read"
  on public.families for select
  using (public.is_member_of_family(id));

create policy "families: creator inserts"
  on public.families for insert
  with check (created_by = auth.uid());

create policy "families: parents update"
  on public.families for update
  using (public.is_parent_of_family(id))
  with check (public.is_parent_of_family(id));

create policy "families: creator deletes"
  on public.families for delete
  using (created_by = auth.uid());

-- ---------------------------------------------------------------------------
-- family_members
-- ---------------------------------------------------------------------------
create policy "family_members: members and self read"
  on public.family_members for select
  using (public.is_member_of_family(family_id) or profile_id = auth.uid());

create policy "family_members: creator or parent writes"
  on public.family_members for insert
  with check (
    public.is_creator_of_family(family_id) or public.is_parent_of_family(family_id)
  );

create policy "family_members: creator manages roles"
  on public.family_members for update
  using (public.is_creator_of_family(family_id))
  with check (public.is_creator_of_family(family_id));

create policy "family_members: self leaves or creator removes"
  on public.family_members for delete
  using (profile_id = auth.uid() or public.is_creator_of_family(family_id));

-- ---------------------------------------------------------------------------
-- children
-- ---------------------------------------------------------------------------
create policy "children: parents read, device reads self"
  on public.children for select
  using (public.is_parent_of_family(family_id) or public.is_device_of_child(id));

create policy "children: parents insert"
  on public.children for insert
  with check (public.is_parent_of_family(family_id));

create policy "children: parents update"
  on public.children for update
  using (public.is_parent_of_family(family_id))
  with check (public.is_parent_of_family(family_id));

create policy "children: parents delete"
  on public.children for delete
  using (public.is_parent_of_family(family_id));

-- ---------------------------------------------------------------------------
-- devices
-- NOTE: there is deliberately no INSERT policy. Device rows are created only
-- by the pairing-verify Edge Function (service_role). Deny by default.
-- ---------------------------------------------------------------------------
create policy "devices: parent reads family devices, device reads itself"
  on public.devices for select
  using (public.is_parent_of_child(child_id) or user_id = auth.uid());

create policy "devices: parent manages, device heartbeats itself"
  on public.devices for update
  using (public.is_parent_of_child(child_id) or user_id = auth.uid())
  with check (public.is_parent_of_child(child_id) or user_id = auth.uid());

create policy "devices: parent revokes"
  on public.devices for delete
  using (public.is_parent_of_child(child_id));

-- ---------------------------------------------------------------------------
-- pairing_codes — invisible to every client role.
-- Written/read only by pairing Edge Functions (service_role, BYPASSRLS).
-- ---------------------------------------------------------------------------
create policy "pairing_codes: deny all client access"
  on public.pairing_codes for select
  using (false);

-- ---------------------------------------------------------------------------
-- rules / app_limits / schedules / web_filters
-- Parents write, the paired device reads its own copy. The device can never
-- widen its own permissions.
-- ---------------------------------------------------------------------------
create policy "rules: parent rw, device read"
  on public.rules for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

create policy "rules: parents insert"
  on public.rules for insert
  with check (public.is_parent_of_child(child_id));

create policy "rules: parents update"
  on public.rules for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

create policy "rules: parents delete"
  on public.rules for delete
  using (public.is_parent_of_child(child_id));

create policy "app_limits: parent rw, device read"
  on public.app_limits for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

create policy "app_limits: parents insert"
  on public.app_limits for insert
  with check (public.is_parent_of_child(child_id));

create policy "app_limits: parents update"
  on public.app_limits for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

create policy "app_limits: parents delete"
  on public.app_limits for delete
  using (public.is_parent_of_child(child_id));

create policy "schedules: parent rw, device read"
  on public.schedules for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

create policy "schedules: parents insert"
  on public.schedules for insert
  with check (public.is_parent_of_child(child_id));

create policy "schedules: parents update"
  on public.schedules for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

create policy "schedules: parents delete"
  on public.schedules for delete
  using (public.is_parent_of_child(child_id));

create policy "web_filters: parent rw, device read"
  on public.web_filters for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

create policy "web_filters: parents insert"
  on public.web_filters for insert
  with check (public.is_parent_of_child(child_id));

create policy "web_filters: parents update"
  on public.web_filters for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

create policy "web_filters: parents delete"
  on public.web_filters for delete
  using (public.is_parent_of_child(child_id));

-- ---------------------------------------------------------------------------
-- usage_events — append-only facts from the paired device only.
-- No UPDATE policy: history is immutable from clients.
-- ---------------------------------------------------------------------------
create policy "usage_events: device inserts own"
  on public.usage_events for insert
  with check (
    public.is_authenticated_user()
    and child_id = public.my_child_id()
    and device_id = public.my_device_id()
  );

create policy "usage_events: parents read family"
  on public.usage_events for select
  using (public.is_parent_of_child(child_id));

create policy "usage_events: parents trim own data"
  on public.usage_events for delete
  using (public.is_parent_of_child(child_id));

-- ---------------------------------------------------------------------------
-- usage_daily — written only by the report-rollup job (service_role).
-- ---------------------------------------------------------------------------
create policy "usage_daily: parents read rollups"
  on public.usage_daily for select
  using (public.is_parent_of_child(child_id));

create policy "usage_daily: system inserts"
  on public.usage_daily for insert
  with check (auth.role() = 'service_role');

create policy "usage_daily: system updates"
  on public.usage_daily for update
  using (auth.role() = 'service_role')
  with check (auth.role() = 'service_role');

create policy "usage_daily: system deletes"
  on public.usage_daily for delete
  using (auth.role() = 'service_role');

-- ---------------------------------------------------------------------------
-- locations — device writes its own fixes, parents read.
-- ---------------------------------------------------------------------------
create policy "locations: device inserts own"
  on public.locations for insert
  with check (
    public.is_authenticated_user()
    and child_id = public.my_child_id()
    and device_id = public.my_device_id()
  );

create policy "locations: parents read family"
  on public.locations for select
  using (public.is_parent_of_child(child_id));

create policy "locations: parents delete own history"
  on public.locations for delete
  using (public.is_parent_of_child(child_id));

-- ---------------------------------------------------------------------------
-- time_requests — device creates while pending; parents read.
-- Decisions (approve/deny) are made by the time-request-decide Edge Function
-- with service_role, so a child device cannot forge a grant. The update policy
-- only lets a device expire its own still-pending request.
-- ---------------------------------------------------------------------------
create policy "time_requests: device creates pending"
  on public.time_requests for insert
  with check (
    public.is_authenticated_user()
    and child_id = public.my_child_id()
    and device_id = public.my_device_id()
    and status = 'pending'
    and granted_seconds is null
    and decided_at is null
  );

create policy "time_requests: parents and device read"
  on public.time_requests for select
  using (public.is_parent_of_child(child_id) or public.is_device_of_child(child_id));

create policy "time_requests: device expires own pending request"
  on public.time_requests for update
  using (public.is_device_of_child(child_id) and status = 'pending')
  with check (
    public.is_device_of_child(child_id)
    and status in ('pending', 'expired')
    and granted_seconds is null
    and decided_by is null
  );

-- ---------------------------------------------------------------------------
-- audit_log — append-only trail for the family.
-- ---------------------------------------------------------------------------
create policy "audit_log: family reads"
  on public.audit_log for select
  using (public.is_parent_of_family(family_id));

create policy "audit_log: parents append"
  on public.audit_log for insert
  with check (
    public.is_parent_of_family(family_id)
    and actor_type = 'parent'
    and (actor_id is null or actor_id = auth.uid())
  );

create policy "audit_log: system appends"
  on public.audit_log for insert
  with check (auth.role() = 'service_role');

-- No UPDATE and no DELETE policy: the trail is immutable from clients.
-- Retention cleanup runs as service_role (BYPASSRLS).

-- ---------------------------------------------------------------------------
-- Realtime: postgres_changes applies these same policies to subscribers, so a
-- child device subscribed to child:{id} only receives its own rows (M4 wires
-- the channels up; nothing to configure here yet).
-- ---------------------------------------------------------------------------
