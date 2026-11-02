-- ============================================================================
-- 20261102000000_uninstall_requests.sql — M2c: parent-initiated uninstall requests
-- ============================================================================

-- ---------------------------------------------------------------------------
-- uninstall_requests — parent-initiated request to uninstall a non-system app
-- on the child's device.  Four-way RLS matrix (same pattern as §3.3):
--   • child may update status of its own requests only; may NOT create.
--   • parent may insert/update/delete requests for children in their family.
--   • anon / cross-family reads filtered out silently (0 rows).
--   • service_role (retention jobs) may BYPASSRLS for housekeeping.
-- ---------------------------------------------------------------------------

create table public.uninstall_requests (
  id            uuid primary key default gen_random_uuid(),
  child_id      uuid not null references public.children (id) on delete cascade,
  package_name  text not null check (char_length(package_name) between 1 and 200),
  app_name      text not null default '',
  requested_by  uuid not null references public.profiles (id),
  status        text not null default 'pending'
    check (status in ('pending', 'confirmed', 'declined', 'done', 'failed', 'expired')),
  result_message text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (child_id, package_name)
);

create index uninstall_requests_child_idx on public.uninstall_requests (child_id);
create index uninstall_requests_package_idx on public.uninstall_requests (package_name);

alter table public.uninstall_requests enable row level security;

-- Parent reads/writes requests for children in their family.
create policy "uninstall_requests: parent reads own family"
  on public.uninstall_requests for select
  using (public.is_parent_of_child(child_id));

create policy "uninstall_requests: parent inserts own family"
  on public.uninstall_requests for insert
  with check (public.is_authenticated_user() and public.is_parent_of_child(child_id));

create policy "uninstall_requests: parent updates own family"
  on public.uninstall_requests for update
  using (public.is_parent_of_child(child_id))
  with check (public.is_parent_of_child(child_id));

create policy "uninstall_requests: parent removes own family"
  on public.uninstall_requests for delete
  using (public.is_parent_of_child(child_id));

-- Child may only update the status of requests that belong to its own device.
-- The device_id correlation is enforced at the application logic level; RLS pins
-- the row to the child's child_id+device_id pair.
create policy "uninstall_requests: child updates own status"
  on public.uninstall_requests for update
  using (
    public.is_authenticated_user()
    and device_id = public.my_device_id()
    and public.is_device_of_child(child_id)
  )
  with check (
    public.is_authenticated_user()
    and device_id = public.my_device_id()
    and public.is_device_of_child(child_id)
  );

-- No child INSERT policy: the child cannot create uninstall requests;
-- only the parent can.

-- Realtime: join the supabase_realtime publication so the child's SyncEngine
-- picks up new rows immediately (same guard shape as installed_apps / blocked_apps).
do $$\nbegin\n  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then\n    create publication supabase_realtime;\n  end if;\n  if not exists (\n    select 1\n      from pg_publication_tables\n     where pubname = 'supabase_realtime'\n       and schemaname = 'public'\n       and tablename = 'uninstall_requests'\n  ) then\n    alter publication supabase_realtime add table public.uninstall_requests;\n  end if;\nexec\n  alter table public.uninstall_requests replica identity full;\nend $$;