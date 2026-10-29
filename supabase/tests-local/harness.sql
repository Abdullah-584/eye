-- ============================================================================
-- LOCAL RLS TEST HARNESS — vanilla Postgres only. DO NOT RUN ON SUPABASE.
--
-- `supabase test db` already provides the auth schema, the anon/authenticated/
-- service_role roles and the default grants. This file recreates just enough
-- of that on a plain Postgres so the migration chain and the RLS matrix in
-- rls_matrix.sql can be verified without Docker:
--
--   psql "$DATABASE_URL" \
--     -f supabase/tests-local/harness.sql \
--     -f supabase/migrations/20260101000000_init.sql \
--     -f supabase/migrations/20260101000100_rls_helpers.sql \
--     -f supabase/migrations/20260101000200_rls_policies.sql \
--     -f supabase/tests-local/rls_matrix.sql
--
-- (These files live in tests-local/, deliberately OUTSIDE supabase/tests/: the
-- CLI's `supabase test db` globs everything under supabase/tests and would
-- try to run this plain-SQL harness as pgTAP.)
-- ============================================================================

-- Roles Supabase normally pre-creates.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin bypassrls;
  end if;
end;
$$;

-- The auth schema: only what our migrations touch.
create schema if not exists auth;

create table if not exists auth.users (
  id                   uuid primary key default gen_random_uuid(),
  email                text,
  raw_user_meta_data   jsonb not null default '{}'::jsonb,
  raw_app_meta_data    jsonb not null default '{}'::jsonb,
  aud                  text default 'authenticated',
  role                 text default 'authenticated',
  created_at           timestamptz not null default now()
);

-- Supabase's auth.uid()/auth.role() read the JWT claims PostgREST sets.
create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(
    coalesce(
      nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub',
      nullif(current_setting('request.jwt.claim.sub', true), '')
    ),
    ''
  )::uuid;
$$;

create or replace function auth.role()
returns text
language sql
stable
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role',
    nullif(current_setting('request.jwt.claim.role', true), ''),
    'anon'
  );
$$;

-- Default Supabase grants: full table privileges on public, guarded only by
-- RLS. This is what makes "RLS is the real boundary" true.
grant usage on schema public to anon, authenticated, service_role;
grant usage on schema auth to anon, authenticated, service_role;
alter default privileges in schema public
  grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public
  grant usage, select on sequences to anon, authenticated, service_role;

create schema if not exists tests;

-- Test helper: pretend to be a JWT subject for the rest of the transaction.
create or replace function tests.authenticate_as(p_sub uuid, p_role text default 'authenticated')
returns void
language plpgsql
as $$
begin
  perform set_config('role', p_role, true);
  perform set_config(
    'request.jwt.claims',
    json_build_object('sub', coalesce(p_sub::text, ''), 'role', p_role)::text,
    true
  );
end;
$$;

create or replace function tests.become_service()
returns void
language plpgsql
as $$
begin
  perform set_config('role', 'service_role', true);
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
end;
$$;
