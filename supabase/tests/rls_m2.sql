-- ============================================================================
-- pgTAP: public.installed_apps + public.blocked_apps RLS (M2b)
--
-- Run with:  supabase test db
--
-- Four-subject matrix per docs/M2b-PLAN.md §6.2: the paired device, its own
-- parent, another family's parent, and anon — across BOTH tables. The point
-- of the split (M2b-PLAN §2.4) is structural: the device has no write path to
-- block state, and the parent has no write path to inventory. This suite is
-- the proof that the split holds.
--
-- INSERT with no passing WITH CHECK raises 42501; UPDATE/DELETE with no
-- matching USING filter rows silently (0 affected) — asserted accordingly,
-- same as rls_admin_events.sql.
-- ============================================================================

begin;

create extension if not exists pgtap;

create schema if not exists tests;
create or replace function tests.authenticate_as(p_sub uuid, p_role text default 'authenticated')
returns void
language plpgsql
as $$
begin
  perform set_config('role', p_role, true);
  perform set_config('request.jwt.claims', json_build_object('sub', coalesce(p_sub::text, ''), 'role', p_role)::text, true);
end;
$$;

select plan(30);

-- ---------------------------------------------------------------------------
-- Fixtures. Family/child ids reuse the shared (idempotent) fixtures from
-- rls_admin_events.sql; the TWO device users + device ids are unique to this
-- suite (devices.user_id is unique, and this suite needs a second paired
-- phone to prove per-device update filtering).
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, raw_user_meta_data) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parenta@example.com', '{}'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parentc@example.com', '{}'),
  ('ffffffff-ffff-4fff-8fff-fffffffffff1', 'device1.m2b@example.com', '{"role":"device"}'),
  ('ffffffff-ffff-4fff-8fff-fffffffffff2', 'device2.m2b@example.com', '{"role":"device"}')
on conflict (id) do nothing;

insert into public.families (id, name, created_by) values
  ('f0000000-0000-4000-8000-000000000001', 'Family A', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  ('f0000000-0000-4000-8000-000000000002', 'Family B', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
on conflict (id) do nothing;

insert into public.family_members (family_id, profile_id, role) values
  ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parent'),
  ('f0000000-0000-4000-8000-000000000002', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parent')
on conflict (family_id, profile_id) do nothing;

insert into public.children (id, family_id, display_name) values
  ('c0000000-0000-4000-8000-0000000000a1', 'f0000000-0000-4000-8000-000000000001', 'EmuKid'),
  ('c0000000-0000-4000-8000-0000000000b1', 'f0000000-0000-4000-8000-000000000002', 'Kid B')
on conflict (id) do nothing;

-- Two paired phones for the SAME child (M2b-PLAN §2.3: inventory is per
-- device, one child can have two phones).
insert into public.devices (id, child_id, user_id, device_name, status) values
  ('d1000000-0000-4000-8000-0000000000a1',
   'c0000000-0000-4000-8000-0000000000a1',
   'ffffffff-ffff-4fff-8fff-fffffffffff1', 'Phone 1 (M2b)', 'active'),
  ('d1000000-0000-4000-8000-0000000000a2',
   'c0000000-0000-4000-8000-0000000000a1',
   'ffffffff-ffff-4fff-8fff-fffffffffff2', 'Phone 2 (M2b)', 'active')
on conflict (id) do nothing;

-- ===========================================================================
-- installed_apps
-- ===========================================================================

-- 1. RLS enabled.
select ok(
  (select relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'installed_apps'),
  'installed_apps has row level security enabled'
);

-- 2. Device 1 reports its own inventory row.
reset role;
select tests.authenticate_as('ffffffff-ffff-4fff-8fff-fffffffffff1');
insert into public.installed_apps (child_id, device_id, package_name, app_name, is_system, version_name)
values ('c0000000-0000-4000-8000-0000000000a1',
        'd1000000-0000-4000-8000-0000000000a1',
        'com.example.youtube', 'YouTube', true, '19.0');
select is(
  (select count(*) from public.installed_apps where package_name = 'com.example.youtube'),
  1::bigint,
  'device inserts its own installed_apps row'
);

-- 3. ...but it cannot attach a row to a child it does not serve.
select throws_ok(
  'insert into public.installed_apps (child_id, device_id, package_name)
     values (''c0000000-0000-4000-8000-0000000000b1'',
             ''d1000000-0000-4000-8000-0000000000a1'',
             ''com.example.forge'')',
  '42501', null,
  'device cannot report inventory for another child'
);

-- 4. ...nor stamp a row with a device_id it does not own.
select throws_ok(
  'insert into public.installed_apps (child_id, device_id, package_name)
     values (''c0000000-0000-4000-8000-0000000000a1'',
             ''d1000000-0000-4000-8000-0000000000a2'',
             ''com.example.spoof'')',
  '42501', null,
  'device cannot write rows under another device_id'
);

-- 5. Device 1 refreshes its own row (last_seen heartbeat / reinstall).
with changed as (
  update public.installed_apps set last_seen = now()
   where device_id = 'd1000000-0000-4000-8000-0000000000a1'
     and package_name = 'com.example.youtube' returning id
)
select is((select count(*) from changed), 1::bigint,
  'device updates its own inventory row');

-- Setup: a row owned by device 2 (same child), inserted as superuser.
reset role;
insert into public.installed_apps (child_id, device_id, package_name, app_name)
values ('c0000000-0000-4000-8000-0000000000a1',
        'd1000000-0000-4000-8000-0000000000a2',
        'com.example.chrome', 'Chrome')
on conflict (device_id, package_name) do nothing;

-- 6. Device 1 cannot touch device 2's row (same child, other phone).
reset role;
select tests.authenticate_as('ffffffff-ffff-4fff-8fff-fffffffffff1');
with changed as (
  update public.installed_apps set app_name = 'hijacked'
   where device_id = 'd1000000-0000-4000-8000-0000000000a2' returning id
)
select is((select count(*) from changed), 0::bigint,
  'device cannot update another device''s inventory row');

-- 7. Parent A reads both phones'' inventory.
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
select is(
  (select count(*) from public.installed_apps), 2::bigint,
  'parent reads own child''s full inventory (both devices)'
);

-- 8. Another family's parent reads none of it.
reset role;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
select is(
  (select count(*) from public.installed_apps), 0::bigint,
  'unrelated parent reads no inventory'
);

-- 9. Parent has NO write path to inventory: INSERT raises, UPDATE/DELETE filter.
select throws_ok(
  'insert into public.installed_apps (child_id, device_id, package_name)
     values (''c0000000-0000-4000-8000-0000000000a1'',
             ''d1000000-0000-4000-8000-0000000000a1'',
             ''com.example.parentfake'')',
  '42501', null,
  'parent cannot insert inventory rows'
);
with changed as (
  update public.installed_apps set app_name = 'parentedit' returning id
)
select is((select count(*) from changed), 0::bigint,
  'parent cannot update inventory rows');
with deleted as (
  delete from public.installed_apps returning id
)
select is((select count(*) from deleted), 0::bigint,
  'parent cannot delete inventory rows');

-- 10. Anon reads nothing and cannot write.
reset role;
select tests.authenticate_as(null, 'anon');
select is(
  (select count(*) from public.installed_apps), 0::bigint,
  'anon reads no inventory'
);
select throws_ok(
  'insert into public.installed_apps (child_id, device_id, package_name)
     values (''c0000000-0000-4000-8000-0000000000a1'',
             ''d1000000-0000-4000-8000-0000000000a1'',
             ''com.example.anon'')',
  '42501', null,
  'anon cannot insert inventory rows'
);

-- ===========================================================================
-- blocked_apps
-- ===========================================================================

-- 11. RLS enabled.
select ok(
  (select relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'blocked_apps'),
  'blocked_apps has row level security enabled'
);

-- 12. Parent A blocks an app; version starts at 1, updated_by is the parent.
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
insert into public.blocked_apps (child_id, package_name, blocked)
values ('c0000000-0000-4000-8000-0000000000a1', 'com.example.youtube', true);
select is(
  (select version from public.blocked_apps where package_name = 'com.example.youtube'),
  1::int,
  'a fresh block starts at version 1'
);
select is(
  (select updated_by from public.blocked_apps where package_name = 'com.example.youtube'),
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
  'updated_by defaults to the blocking parent'
);

-- 13. Toggling bumps version via the touch trigger (realtime ordering guard).
update public.blocked_apps set blocked = false
 where package_name = 'com.example.youtube';
select is(
  (select version from public.blocked_apps where package_name = 'com.example.youtube'),
  2::int,
  'touch trigger bumps version on update'
);

-- 14. Device 1 reads the block set (read-only) but has no write path.
reset role;
select tests.authenticate_as('ffffffff-ffff-4fff-8fff-fffffffffff1');
select is(
  (select count(*) from public.blocked_apps), 1::bigint,
  'device reads the child block set'
);
select throws_ok(
  'insert into public.blocked_apps (child_id, package_name, blocked)
     values (''c0000000-0000-4000-8000-0000000000a1'', ''com.example.self'', false)',
  '42501', null,
  'device cannot insert a block row'
);
with changed as (
  update public.blocked_apps set blocked = false returning id
)
select is((select count(*) from changed), 0::bigint,
  'device cannot self-unblock (update filtered out)');
with deleted as (
  delete from public.blocked_apps returning id
)
select is((select count(*) from deleted), 0::bigint,
  'device cannot delete a block row');

-- 15. Another family's parent is fully locked out of this child's blocks.
reset role;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
select is(
  (select count(*) from public.blocked_apps), 0::bigint,
  'unrelated parent reads no blocks'
);
select throws_ok(
  'insert into public.blocked_apps (child_id, package_name)
     values (''c0000000-0000-4000-8000-0000000000a1'', ''com.example.crosstenant'')',
  '42501', null,
  'unrelated parent cannot insert a block'
);
with changed as (
  update public.blocked_apps set blocked = false returning id
)
select is((select count(*) from changed), 0::bigint,
  'unrelated parent cannot update a block');

-- 16. Anon reads nothing and cannot write.
reset role;
select tests.authenticate_as(null, 'anon');
select is(
  (select count(*) from public.blocked_apps), 0::bigint,
  'anon reads no blocks'
);
select throws_ok(
  'insert into public.blocked_apps (child_id, package_name)
     values (''c0000000-0000-4000-8000-0000000000a1'', ''com.example.anon'')',
  '42501', null,
  'anon cannot insert a block'
);

-- ===========================================================================
-- Realtime publication (both tables must stream to SyncEngine).
-- ===========================================================================
select ok(
  (select count(*) from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public'
      and tablename = 'installed_apps') = 1,
  'installed_apps is in the supabase_realtime publication'
);
select ok(
  (select count(*) from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public'
      and tablename = 'blocked_apps') = 1,
  'blocked_apps is in the supabase_realtime publication'
);
select is(
  (select c.relreplident from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'installed_apps'),
  'f'::"char",
  'installed_apps uses replica identity full (child_id is not the PK)'
);
select is(
  (select c.relreplident from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'blocked_apps'),
  'f'::"char",
  'blocked_apps uses replica identity full (child_id is not the PK)'
);

select * from finish();
rollback;
