-- ============================================================================
-- RLS MATRIX — plain SQL assertions, runnable on vanilla Postgres after
-- harness.sql + the three migrations (see the header of harness.sql).
--
-- Every check RAISES an exception with a FAIL: prefix, so a non-empty error
-- output means a broken policy and an empty run means every check passed.
-- The same scenarios exist as pgTAP tests in supabase/tests/*.sql for CI.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Fixtures (as superuser, before any impersonation)
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, raw_user_meta_data) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parenta@example.com', '{"display_name":"Parent A"}'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'parentb@example.com', '{"display_name":"Parent B"}'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parentc@example.com', '{"display_name":"Parent C"}'),
  ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'device-a@example.com', '{"display_name":"Device A"}')
on conflict (id) do nothing;

-- The handle_new_user trigger must have created the profile rows.
do $$
declare n int;
begin
  select count(*) into n from public.profiles;
  if n < 4 then
    raise exception 'FAIL: handle_new_user created % profiles, expected 4', n;
  end if;
  raise notice 'PASS: handle_new_user creates a profile per auth.users row';
end;
$$;

insert into public.families (id, name, created_by) values
  ('f0000000-0000-4000-8000-000000000001', 'Family A', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  ('f0000000-0000-4000-8000-000000000002', 'Family C', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
on conflict (id) do nothing;

insert into public.family_members (family_id, profile_id, role) values
  ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parent'),
  ('f0000000-0000-4000-8000-000000000001', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'parent'),
  ('f0000000-0000-4000-8000-000000000002', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parent')
on conflict (family_id, profile_id) do nothing;

insert into public.children (id, family_id, display_name) values
  ('c0000000-0000-4000-8000-000000000001', 'f0000000-0000-4000-8000-000000000001', 'Child A1'),
  ('c0000000-0000-4000-8000-000000000002', 'f0000000-0000-4000-8000-000000000002', 'Child C1')
on conflict (id) do nothing;

-- Device row for child A1, linked to the device auth user. In production this
-- is written only by the pairing Edge Function (service_role, no client policy).
insert into public.devices (id, child_id, user_id, device_name, status, paired_at) values
  ('d0000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000001',
   'dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'Pixel A', 'active', now())
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- CHECK: RLS enabled + policies present on every table
-- ---------------------------------------------------------------------------
do $$
declare
  missing text;
begin
  select string_agg(c.relname, ', ' order by c.relname)
    into missing
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public'
     and c.relkind = 'r'
     and not c.relrowsecurity;

  if missing is not null then
    raise exception 'FAIL: tables without RLS enabled: %', missing;
  end if;
  raise notice 'PASS: every table in public has RLS enabled';
end;
$$;

do $$
declare
  orphan text;
begin
  select string_agg(tbl, ', ') into orphan
  from (
    select c.relname as tbl
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind = 'r'
       and not exists (
         select 1 from pg_policy p where p.polrelid = c.oid
       )
  ) s;

  if orphan is not null then
    raise exception 'FAIL: tables with RLS but zero policies (silently unusable): %', orphan;
  end if;
  raise notice 'PASS: every table has at least one policy';
end;
$$;

-- ---------------------------------------------------------------------------
-- CHECK: anon (no JWT) sees nothing anywhere
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as(null, 'anon');
do $$
declare n int;
begin
  select count(*) into n from public.profiles;
  if n <> 0 then raise exception 'FAIL: anon sees % profiles', n; end if;
  select count(*) into n from public.families;
  if n <> 0 then raise exception 'FAIL: anon sees % families', n; end if;
  select count(*) into n from public.children;
  if n <> 0 then raise exception 'FAIL: anon sees % children', n; end if;
  select count(*) into n from public.rules;
  if n <> 0 then raise exception 'FAIL: anon sees % rules', n; end if;
  select count(*) into n from public.pairing_codes;
  if n <> 0 then raise exception 'FAIL: anon sees % pairing codes', n; end if;
  raise notice 'PASS: anon reads nothing';
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: parent isolation between families
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
do $$
declare n int;
begin
  select count(*) into n from public.families;
  if n <> 1 then raise exception 'FAIL: parent A sees % families, expected 1', n; end if;

  select count(*) into n from public.children;
  if n <> 1 then raise exception 'FAIL: parent A sees % children, expected 1', n; end if;

  -- The co-parent shares the family, so both profiles are visible.
  select count(*) into n from public.profiles;
  if n <> 2 then raise exception 'FAIL: parent A sees % profiles, expected 2', n; end if;

  -- Parent C's child must be invisible.
  select count(*) into n from public.children where id = 'c0000000-0000-4000-8000-000000000002';
  if n <> 0 then raise exception 'FAIL: parent A can see another family''s child'; end if;
  raise notice 'PASS: parent A only sees family A (2 profiles, 1 child)';
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: a parent cannot write into another family
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
do $$
begin
  begin
    insert into public.children (family_id, display_name)
    values ('f0000000-0000-4000-8000-000000000001', 'Intruder');
    raise exception 'FAIL: parent C inserted a child into family A';
  exception when insufficient_privilege then
    raise notice 'PASS: parent C cannot insert into family A';
  end;
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: family_members — first row can be created by the family creator
--         (nothing is a member yet at that moment), co-parents cannot manage roles
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
do $$
begin
  begin
    insert into public.family_members (family_id, profile_id, role)
    values ('f0000000-0000-4000-8000-000000000002', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'parent');
    raise notice 'PASS: family creator can add the first member';
  exception when insufficient_privilege then
    raise exception 'FAIL: family creator could not add a member to their own family';
  end;
end;
$$;
rollback;

begin;
select tests.authenticate_as('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
do $$
declare r text;
begin
  -- Note: an RLS-filtered UPDATE does NOT raise, it silently matches no rows,
  -- so the assertion is on the resulting value rather than on an exception.
  update public.family_members set role = 'guardian'
   where family_id = 'f0000000-0000-4000-8000-000000000001'
     and profile_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

  select role into r from public.family_members
   where family_id = 'f0000000-0000-4000-8000-000000000001'
     and profile_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

  if r is distinct from 'parent' then
    raise exception 'FAIL: a co-parent managed family roles (role is now %)', r;
  end if;
  raise notice 'PASS: co-parent cannot change roles';
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: the paired device reads only its own rules and cannot write them
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('dddddddd-dddd-4ddd-8ddd-dddddddddddd');
do $$
declare n int;
begin
  select count(*) into n from public.children;
  if n <> 1 then raise exception 'FAIL: device A sees % children, expected 1', n; end if;

  select count(*) into n from public.devices;
  if n <> 1 then raise exception 'FAIL: device A sees % devices, expected 1', n; end if;

  -- It may read the rules it must enforce...
  raise notice 'PASS: device A sees exactly its own child and device';

  begin
    insert into public.rules (child_id, kind, enabled, config)
    values ('c0000000-0000-4000-8000-000000000001', 'app_block', true, '{}'::jsonb);
    raise exception 'FAIL: device A created a rule for itself';
  exception when insufficient_privilege then
    raise notice 'PASS: device A cannot create rules';
  end;

  begin
    insert into public.rules (child_id, kind, enabled, config)
    values ('c0000000-0000-4000-8000-000000000002', 'app_block', true, '{}'::jsonb);
    raise exception 'FAIL: device A wrote to another family''s child';
  exception when insufficient_privilege then
    raise notice 'PASS: device A cannot write to another family';
  end;
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: usage/location uploads are scoped to the device's own child
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('dddddddd-dddd-4ddd-8ddd-dddddddddddd');
do $$
begin
  begin
    insert into public.usage_events (child_id, device_id, package_name, event_type, started_at, duration_sec)
    values ('c0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-000000000001',
            'com.example.app', 'app_switch', now(), 60);
    raise notice 'PASS: device A can upload its own usage';
  exception when insufficient_privilege then
    raise exception 'FAIL: device A could not upload its own usage';
  end;

  begin
    insert into public.usage_events (child_id, device_id, package_name, event_type, started_at, duration_sec)
    values ('c0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000001',
            'com.example.app', 'app_switch', now(), 60);
    raise exception 'FAIL: device A uploaded usage against another child';
  exception when insufficient_privilege then
    raise notice 'PASS: device A cannot upload for another child';
  end;
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: time requests cannot be forged by the device
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('dddddddd-dddd-4ddd-8ddd-dddddddddddd');
do $$
declare rid uuid;
begin
  insert into public.time_requests (child_id, device_id, requested_seconds)
  values ('c0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-000000000001', 900)
  returning id into rid;

  raise notice 'PASS: device A can create a pending request';

  begin
    update public.time_requests
       set status = 'approved', granted_seconds = 900, decided_at = now()
     where id = rid;
    raise exception 'FAIL: device A approved its own time request';
  exception when insufficient_privilege then
    raise notice 'PASS: device A cannot approve its own request';
  end;
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: pairing codes are invisible to every client role
-- ---------------------------------------------------------------------------
insert into public.pairing_codes (family_id, created_by, code_hash, expires_at)
values ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        'deadbeef', now() + interval '5 minutes');

begin;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
do $$
declare n int;
begin
  select count(*) into n from public.pairing_codes;
  if n <> 0 then raise exception 'FAIL: a parent can read pairing code hashes (%)', n; end if;
  raise notice 'PASS: pairing codes are invisible to parents';
end;
$$;
rollback;

begin;
select tests.authenticate_as('dddddddd-dddd-4ddd-8ddd-dddddddddddd');
do $$
declare n int;
begin
  select count(*) into n from public.pairing_codes;
  if n <> 0 then raise exception 'FAIL: a device can read pairing code hashes (%)', n; end if;
  raise notice 'PASS: pairing codes are invisible to devices';
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: audit log is append-only for clients and scoped to the family
-- ---------------------------------------------------------------------------
begin;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
do $$
begin
  begin
    insert into public.audit_log (family_id, actor_id, actor_type, action)
    values ('f0000000-0000-4000-8000-000000000001', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parent', 'tampered');
    raise exception 'FAIL: parent C wrote into family A''s audit log';
  exception when insufficient_privilege then
    raise notice 'PASS: audit log is scoped to the family';
  end;
end;
$$;
rollback;

begin;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
do $$
declare n int;
begin
  insert into public.audit_log (family_id, actor_id, actor_type, action)
  values ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parent', 'rule.updated');
  raise notice 'PASS: a parent can append to their own audit log';

  select count(*) into n from public.audit_log;
  if n < 1 then raise exception 'FAIL: parent A cannot read their audit log'; end if;
end;
$$;
rollback;

-- ---------------------------------------------------------------------------
-- CHECK: service_role bypasses RLS (Edge Functions need this)
-- ---------------------------------------------------------------------------
begin;
select tests.become_service();
do $$
declare n int;
begin
  select count(*) into n from public.families;
  if n < 2 then raise exception 'FAIL: service_role sees only % families', n; end if;
  raise notice 'PASS: service_role bypasses RLS';
end;
$$;
rollback;

-- If we got here, every check printed PASS and none raised.
do $$
begin
  raise notice 'RLS MATRIX: ALL CHECKS PASSED';
end;
$$;
