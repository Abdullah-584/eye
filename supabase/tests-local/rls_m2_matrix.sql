-- ============================================================================
-- RLS MATRIX M2b — plain SQL assertions, runnable on vanilla Postgres after
-- harness.sql + the full migration chain (see the header of harness.sql).
--
-- Every check RAISES an exception with a FAIL: prefix, so a non-empty error
-- output means a broken policy and an empty run means every check passed.
-- The same scenarios exist as pgTAP tests in supabase/tests/rls_m2.sql for CI.
--
-- The structural guarantee under test (docs/M2b-PLAN.md §2.4): the device has
-- NO write path to block state, and the parent has NO write path to inventory.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Fixtures (as superuser, before any impersonation)
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, raw_user_meta_data) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parenta@example.com', '{"display_name":"Parent A"}'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parentc@example.com', '{"display_name":"Parent C"}'),
  ('ffffffff-ffff-4fff-8fff-fffffffffff1', 'device1.m2b@example.com', '{"role":"device"}'),
  ('ffffffff-ffff-4fff-8fff-fffffffffff2', 'device2.m2b@example.com', '{"role":"device"}')
on conflict (id) do nothing;

insert into public.families (id, name, created_by) values
  ('f0000000-0000-4000-8000-000000000001', 'Family A', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  ('f0000000-0000-4000-8000-000000000002', 'Family C', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
on conflict (id) do nothing;

insert into public.family_members (family_id, profile_id, role) values
  ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parent'),
  ('f0000000-0000-4000-8000-000000000002', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parent')
on conflict (family_id, profile_id) do nothing;

insert into public.children (id, family_id, display_name) values
  ('c0000000-0000-4000-8000-0000000000a1', 'f0000000-0000-4000-8000-000000000001', 'EmuKid'),
  ('c0000000-0000-4000-8000-0000000000b1', 'f0000000-0000-4000-8000-000000000002', 'Kid C')
on conflict (id) do nothing;

-- Two paired phones for the SAME child (inventory is per device).
insert into public.devices (id, child_id, user_id, device_name, status) values
  ('d1000000-0000-4000-8000-0000000000a1', 'c0000000-0000-4000-8000-0000000000a1',
   'ffffffff-ffff-4fff-8fff-fffffffffff1', 'Phone 1', 'active'),
  ('d1000000-0000-4000-8000-0000000000a2', 'c0000000-0000-4000-8000-0000000000a1',
   'ffffffff-ffff-4fff-8fff-fffffffffff2', 'Phone 2', 'active')
on conflict (id) do nothing;

-- ===========================================================================
-- installed_apps
-- ===========================================================================
begin;
select tests.authenticate_as('ffffffff-ffff-4fff-8fff-fffffffffff1');
do $$
declare n int;
begin
  insert into public.installed_apps (child_id, device_id, package_name, app_name, is_system, version_name)
  values ('c0000000-0000-4000-8000-0000000000a1', 'd1000000-0000-4000-8000-0000000000a1',
          'com.example.youtube', 'YouTube', true, '19.0');
  select count(*) into n from public.installed_apps where package_name='com.example.youtube';
  if n <> 1 then raise exception 'FAIL: device did not insert its own row (%)', n; end if;
  raise notice 'PASS: device inserts its own installed_apps row';
end $$;

do $$
begin
  begin
    insert into public.installed_apps (child_id, device_id, package_name)
    values ('c0000000-0000-4000-8000-0000000000b1','d1000000-0000-4000-8000-0000000000a1','com.x.forge');
    raise exception 'FAIL: device attached a row to another child';
  exception when insufficient_privilege then
    raise notice 'PASS: device cannot report inventory for another child';
  end;
end $$;

do $$
begin
  begin
    insert into public.installed_apps (child_id, device_id, package_name)
    values ('c0000000-0000-4000-8000-0000000000a1','d1000000-0000-4000-8000-0000000000a2','com.x.spoof');
    raise exception 'FAIL: device wrote a row under another device_id';
  exception when insufficient_privilege then
    raise notice 'PASS: device cannot write rows under another device_id';
  end;
end $$;

-- Seed device 2's row as superuser for cross-device update test.
reset role;
insert into public.installed_apps (child_id, device_id, package_name, app_name)
values ('c0000000-0000-4000-8000-0000000000a1','d1000000-0000-4000-8000-0000000000a2','com.example.chrome','Chrome')
on conflict (device_id, package_name) do nothing;

select tests.authenticate_as('ffffffff-ffff-4fff-8fff-fffffffffff1');
do $$
declare n int;
begin
  with changed as (update public.installed_apps set app_name='hijacked'
                    where device_id='d1000000-0000-4000-8000-0000000000a2' returning id)
  select count(*) into n from changed;
  if n <> 0 then raise exception 'FAIL: device updated another device''s row (%)', n; end if;
  raise notice 'PASS: device cannot update another device''s inventory row';
end $$;

-- Parent A reads both phones; parent C reads none.
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
do $$
declare n int;
begin
  select count(*) into n from public.installed_apps;
  if n <> 2 then raise exception 'FAIL: parent A sees % inventory rows, expected 2', n; end if;
  raise notice 'PASS: parent reads own child''s inventory (both devices)';
end $$;

reset role;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
do $$
declare n int;
begin
  select count(*) into n from public.installed_apps;
  if n <> 0 then raise exception 'FAIL: unrelated parent sees % inventory rows', n; end if;
  raise notice 'PASS: unrelated parent reads no inventory';
end $$;

-- Parent has NO write path to inventory.
do $$
declare n int;
begin
  begin
    insert into public.installed_apps (child_id, device_id, package_name)
    values ('c0000000-0000-4000-8000-0000000000a1','d1000000-0000-4000-8000-0000000000a1','com.x.parentfake');
    raise exception 'FAIL: parent inserted an inventory row';
  exception when insufficient_privilege then
    raise notice 'PASS: parent cannot insert inventory rows';
  end;

  with changed as (update public.installed_apps set app_name='parentedit' returning id)
  select count(*) into n from changed;
  if n <> 0 then raise exception 'FAIL: parent updated inventory rows (%)', n; end if;
  raise notice 'PASS: parent cannot update inventory rows';

  with deleted as (delete from public.installed_apps returning id)
  select count(*) into n from deleted;
  if n <> 0 then raise exception 'FAIL: parent deleted inventory rows (%)', n; end if;
  raise notice 'PASS: parent cannot delete inventory rows';
end $$;

-- Anon reads nothing / cannot write.
reset role;
select tests.authenticate_as(null, 'anon');
do $$
declare n int;
begin
  select count(*) into n from public.installed_apps;
  if n <> 0 then raise exception 'FAIL: anon sees % inventory rows', n; end if;
  raise notice 'PASS: anon reads no inventory';
  begin
    insert into public.installed_apps (child_id, device_id, package_name)
    values ('c0000000-0000-4000-8000-0000000000a1','d1000000-0000-4000-8000-0000000000a1','com.x.anon');
    raise exception 'FAIL: anon inserted an inventory row';
  exception when insufficient_privilege then
    raise notice 'PASS: anon cannot insert inventory rows';
  end;
end $$;
rollback;

-- ===========================================================================
-- blocked_apps
-- ===========================================================================
begin;
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
do $$
declare v int; by uuid;
begin
  insert into public.blocked_apps (child_id, package_name, blocked)
  values ('c0000000-0000-4000-8000-0000000000a1','com.example.youtube', true);
  select version, updated_by into v, by from public.blocked_apps where package_name='com.example.youtube';
  if v <> 1 then raise exception 'FAIL: fresh block version is %, expected 1', v; end if;
  if by is distinct from 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid then
    raise exception 'FAIL: updated_by is %, expected parent A', by; end if;
  raise notice 'PASS: parent blocks; version=1, updated_by=parent A';

  update public.blocked_apps set blocked=false where package_name='com.example.youtube';
  select version into v from public.blocked_apps where package_name='com.example.youtube';
  if v <> 2 then raise exception 'FAIL: touch trigger did not bump version (got %)', v; end if;
  raise notice 'PASS: touch trigger bumps version on update';
end $$;

-- Device reads block set but has no write path.
reset role;
select tests.authenticate_as('ffffffff-ffff-4fff-8fff-fffffffffff1');
do $$
declare n int;
begin
  select count(*) into n from public.blocked_apps;
  if n <> 1 then raise exception 'FAIL: device sees % blocks, expected 1', n; end if;
  raise notice 'PASS: device reads the child block set';

  begin
    insert into public.blocked_apps (child_id, package_name, blocked)
    values ('c0000000-0000-4000-8000-0000000000a1','com.x.self', false);
    raise exception 'FAIL: device inserted a block row';
  exception when insufficient_privilege then
    raise notice 'PASS: device cannot insert a block row';
  end;

  with changed as (update public.blocked_apps set blocked=false returning id)
  select count(*) into n from changed;
  if n <> 0 then raise exception 'FAIL: device self-unblocked (%)', n; end if;
  raise notice 'PASS: device cannot self-unblock (update filtered)';

  with deleted as (delete from public.blocked_apps returning id)
  select count(*) into n from deleted;
  if n <> 0 then raise exception 'FAIL: device deleted a block row (%)', n; end if;
  raise notice 'PASS: device cannot delete a block row';
end $$;

-- Unrelated parent locked out.
reset role;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
do $$
declare n int;
begin
  select count(*) into n from public.blocked_apps;
  if n <> 0 then raise exception 'FAIL: unrelated parent sees % blocks', n; end if;
  raise notice 'PASS: unrelated parent reads no blocks';
  begin
    insert into public.blocked_apps (child_id, package_name)
    values ('c0000000-0000-4000-8000-0000000000a1','com.x.cross');
    raise exception 'FAIL: unrelated parent inserted a block';
  exception when insufficient_privilege then
    raise notice 'PASS: unrelated parent cannot insert a block';
  end;
  with changed as (update public.blocked_apps set blocked=false returning id)
  select count(*) into n from changed;
  if n <> 0 then raise exception 'FAIL: unrelated parent updated a block (%)', n; end if;
  raise notice 'PASS: unrelated parent cannot update a block';
end $$;

-- Anon locked out.
reset role;
select tests.authenticate_as(null, 'anon');
do $$
declare n int;
begin
  select count(*) into n from public.blocked_apps;
  if n <> 0 then raise exception 'FAIL: anon sees % blocks', n; end if;
  raise notice 'PASS: anon reads no blocks';
  begin
    insert into public.blocked_apps (child_id, package_name)
    values ('c0000000-0000-4000-8000-0000000000a1','com.x.anon');
    raise exception 'FAIL: anon inserted a block';
  exception when insufficient_privilege then
    raise notice 'PASS: anon cannot insert a block';
  end;
end $$;
rollback;

do $$
begin
  raise notice 'RLS MATRIX M2b: ALL CHECKS PASSED';
end $$;
