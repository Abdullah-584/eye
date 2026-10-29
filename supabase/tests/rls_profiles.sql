-- ============================================================================
-- pgTAP: public.profiles RLS
--
-- Run with:  supabase test db     (the CLI installs pgTAP and proves this file)
--
-- The identical scenarios are executed here without pgTAP by
-- supabase/tests-local/rls_matrix.sql, so the behaviour is verified in both
-- shapes. Everything below runs inside one transaction and is rolled back.
-- ============================================================================

begin;

create extension if not exists pgtap;

-- Impersonation helper: sets the role and the JWT claims PostgREST would set.
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

select plan(10);

-- ---------------------------------------------------------------------------
-- Fixtures, as superuser
-- ---------------------------------------------------------------------------
-- Fixtures are idempotent: another suite in the same database run may have
-- created them already, and a test must never fail on a duplicate key.
insert into auth.users (id, email, raw_user_meta_data) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parenta@example.com', '{"display_name":"Parent A"}'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'parentb@example.com', '{"display_name":"Parent B"}'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parentc@example.com', '{"display_name":"Parent C"}')
on conflict (id) do nothing;

insert into public.families (id, name, created_by)
values ('f0000000-0000-4000-8000-000000000001', 'Family A', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
on conflict (id) do nothing;

insert into public.family_members (family_id, profile_id, role) values
  ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parent'),
  ('f0000000-0000-4000-8000-000000000001', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'parent')
on conflict (family_id, profile_id) do nothing;

-- 1. The sign-up trigger creates exactly one profile per auth user.
-- Counted by fixture id (not table-wide) so a seeded demo family from
-- supabase/seed.sql cannot make this assertion flaky.
select is(
  (select count(*) from public.profiles
    where id in ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
                 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
                 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')),
  3::bigint,
  'handle_new_user creates a profile for every auth.users row'
);

-- 2. RLS is enabled on profiles.
select ok(
  (select relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'profiles'),
  'profiles has row level security enabled'
);

-- ---------------------------------------------------------------------------
-- Each switch resets to the session user first: authenticate_as() sets the role
-- transaction-locally, and 'authenticated' cannot re-enter the tests schema.
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');

-- 3. A parent reads their own profile.
select is(
  (select count(*) from public.profiles where id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  1::bigint,
  'parent A reads their own profile'
);

-- 4. A parent reads the co-parent they share a family with.
select is(
  (select count(*) from public.profiles where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),
  1::bigint,
  'parent A reads the co-parent in the same family'
);

-- 5. An unrelated parent is invisible.
select is(
  (select count(*) from public.profiles where id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'),
  0::bigint,
  'parent A cannot read a profile from another family'
);

-- 6. Parent A can update their own display name.
update public.profiles set display_name = 'Renamed A'
 where id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
select is(
  (select display_name from public.profiles where id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  'Renamed A',
  'parent A can update their own profile'
);

-- 7. Parent A cannot rename someone else's profile.
-- The UPDATE is checked with RETURNING while impersonating: RLS filters the
-- target row, so zero rows change.
with changed as (
  update public.profiles set display_name = 'Hacked'
   where id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
  returning id
)
select is(
  (select count(*) from changed),
  0::bigint,
  'parent A cannot update another family''s profile (row is filtered out)'
);

-- 8. ...and the stored value really is untouched. Checked as the session user,
-- because the row is invisible to parent A by design (assertion 5).
reset role;
select is(
  (select display_name from public.profiles where id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'),
  'Parent C',
  'the other family''s profile is unchanged after the blocked update'
);

-- ---------------------------------------------------------------------------
reset role;
select tests.authenticate_as(null, 'anon');

-- 9. Anonymous sessions read no profiles at all.
select is(
  (select count(*) from public.profiles),
  0::bigint,
  'anon reads no profiles'
);

-- 10. Anonymous sessions cannot insert a profile.
select throws_ok(
  'insert into public.profiles (id, display_name) values (''99999999-9999-4999-8999-999999999999'', ''Intruder'')',
  '42501',
  null,
  'anon cannot insert profiles'
);

select * from finish();
rollback;
