-- ============================================================================
-- pgTAP: public.admin_events RLS (M2a)
--
-- Run with:  supabase test db
--
-- Four-way matrix per M2-PLAN §7 M2a: the paired device, its own parent,
-- another family's parent, and anon. Append-only: no delete policy, and the
-- parent only acknowledges.
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

select plan(11);

-- ---------------------------------------------------------------------------
-- Fixtures: family A (parent A + parent B), child with one active paired
-- device, and a memberless profile C standing in for an unrelated parent.
-- ---------------------------------------------------------------------------
-- NOTE: profile C and parent ids intentionally collide with the shared
-- fixtures other suites create (idempotent); the *device* user and ids are
-- unique to this suite so two suites cannot fight over devices.user_id
-- (unique constraint), which is why EEEEEEEE… is used here.
insert into auth.users (id, email, raw_user_meta_data) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parenta@example.com', '{}'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'parentb@example.com', '{}'),
  ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'parentc@example.com', '{}'),
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'devicea.m2@example.com', '{"role":"device"}')
on conflict (id) do nothing;

insert into public.families (id, name, created_by) values
  ('f0000000-0000-4000-8000-000000000001', 'Family A', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  ('f0000000-0000-4000-8000-000000000002', 'Family B', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
on conflict (id) do nothing;

insert into public.family_members (family_id, profile_id, role) values
  ('f0000000-0000-4000-8000-000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'parent')
on conflict (family_id, profile_id) do nothing;

insert into public.children (id, family_id, display_name) values
  ('c0000000-0000-4000-8000-0000000000a1', 'f0000000-0000-4000-8000-000000000001', 'EmuKid'),
  ('c0000000-0000-4000-8000-0000000000b1', 'f0000000-0000-4000-8000-000000000002', 'Kid B')
on conflict (id) do nothing;

insert into public.devices (id, child_id, user_id, device_name, status)
values ('d0000000-0000-4000-8000-0000000000a1',
        'c0000000-0000-4000-8000-0000000000a1',
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        'Emulator A (M2)', 'active')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- 1. RLS is enabled.
-- ---------------------------------------------------------------------------
select ok(
  (select relrowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = 'admin_events'),
  'admin_events has row level security enabled'
);

-- ---------------------------------------------------------------------------
-- 2. The paired device reports its own disabled event.
-- ---------------------------------------------------------------------------
reset role;
select tests.authenticate_as('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee');

insert into public.admin_events (child_id, device_id, event_type, occurred_at)
values ('c0000000-0000-4000-8000-0000000000a1',
        'd0000000-0000-4000-8000-0000000000a1',
        'admin_disabled', now());
select is(
  (select count(*) from public.admin_events where parent_ack_at is null),
  1::bigint,
  'device reports its own admin_disabled row'
);

-- 3. ...but it cannot forge a row for some other child/device pair.
select throws_ok(
  'insert into public.admin_events (child_id, device_id, event_type)
     values (''c0000000-0000-4000-8000-0000000000b1'',
             ''d0000000-0000-4000-8000-0000000000a1'',
             ''admin_disabled'')',
  '42501',
  null,
  'device cannot report events for another child'
);

-- ---------------------------------------------------------------------------
-- 4. Parent A sees the alert; another-family parent sees nothing.
-- ---------------------------------------------------------------------------
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
select is(
  (select count(*) from public.admin_events), 1::bigint,
  'parent reads family admin_events'
);

-- 5. Parent acknowledge marks the row instead of deleting it.
update public.admin_events set parent_ack_at = now()
 where child_id = 'c0000000-0000-4000-8000-0000000000a1';
select is(
  (select count(*) from public.admin_events where parent_ack_at is not null), 1::bigint,
  'parent acknowledges an event'
);

reset role;
select tests.authenticate_as('cccccccc-cccc-4ccc-8ccc-cccccccccccc');
select is(
  (select count(*) from public.admin_events), 0::bigint,
  'an unrelated parent reads none of the family admin_events'
);

-- ---------------------------------------------------------------------------
-- 6. Parents cannot fabricate events (they only observe and acknowledge).
-- ---------------------------------------------------------------------------
select throws_ok(
  'insert into public.admin_events (child_id, device_id, event_type)
     values (''c0000000-0000-4000-8000-0000000000a1'',
             ''d0000000-0000-4000-8000-0000000000a1'',
             ''admin_disabled'')',
  '42501',
  null,
  'parent cannot insert admin_events'
);

-- 7. Device may not mutate an existing event. There is deliberately no
--     device UPDATE policy, and UPDATE/DELETE under RLS filter rows
--     silently (0 affected) rather than raising 42501 — assert the truth.
reset role;
select tests.authenticate_as('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee');
with changed as (
  update public.admin_events set occurred_at = now() - interval '1 day' returning id
)
select is((select count(*) from changed), 0::bigint,
  'device cannot update an admin_event');

-- 8. Neither side can delete history (rows are filtered, not raised).
with deleted as (
  delete from public.admin_events returning id
)
select is((select count(*) from deleted), 0::bigint,
  'device cannot delete admin_events');
reset role;
select tests.authenticate_as('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
with deleted as (
  delete from public.admin_events returning id
)
select is((select count(*) from deleted), 0::bigint,
  'parent cannot delete admin_events');

-- 9. Invalid event_type is rejected by the CHECK, even for the device.
reset role;
select tests.authenticate_as('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee');
select throws_ok(
  'insert into public.admin_events (child_id, device_id, event_type)
     values (''c0000000-0000-4000-8000-0000000000a1'',
             ''d0000000-0000-4000-8000-0000000000a1'',
             ''admin_bypass'')',
  '23514',
  null,
  'event_type is constrained to the two states the app understands'
);

select * from finish();
rollback;
