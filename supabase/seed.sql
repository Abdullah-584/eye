-- ============================================================================
-- Development seed data for `supabase start` / `supabase db reset`.
--
-- NEVER run this against a production project: it creates a known password
-- and a demo family. It is git-ignored from production deploys because the
-- Supabase CLI only applies it locally (seed file, not a migration).
-- ============================================================================

create extension if not exists pgcrypto with schema extensions;

-- A demo parent. Inserting into auth.users fires the `on_auth_user_created`
-- trigger from 20260101000000_init.sql, which creates the profile row.
insert into auth.users (
    id, instance_id, aud, role, email, encrypted_password, email_confirmed_at,
    raw_app_meta_data, raw_user_meta_data, created_at, updated_at
)
values (
    '11111111-1111-4111-8111-111111111111',
    '00000000-0000-0000-0000-000000000000',
    'authenticated',
    'authenticated',
    'parent@example.com',
    crypt('password123', gen_salt('bf')),
    now(),
    '{"provider":"email","providers":["email"]}'::jsonb,
    '{"display_name":"Demo Parent"}'::jsonb,
    now(),
    now()
)
on conflict (id) do nothing;

insert into public.families (id, name, created_by)
values ('22222222-2222-4222-8222-222222222222', 'Demo Family', '11111111-1111-4111-8111-111111111111')
on conflict (id) do nothing;

insert into public.family_members (family_id, profile_id, role)
values ('22222222-2222-4222-8222-222222222222', '11111111-1111-4111-8111-111111111111', 'parent')
on conflict (family_id, profile_id) do nothing;

insert into public.children (id, family_id, display_name, timezone)
values (
    '33333333-3333-4333-8333-333333333333',
    '22222222-2222-4222-8222-222222222222',
    'Demo Child',
    'Europe/London'
)
on conflict (id) do nothing;

-- A rule the demo child can see once devices are paired (M3/M4).
insert into public.rules (child_id, kind, enabled, config)
values (
    '33333333-3333-4333-8333-333333333333',
    'daily_total',
    true,
    '{"daily_limit_seconds": 7200}'::jsonb
)
on conflict (id) do nothing;

insert into public.audit_log (family_id, actor_id, actor_type, action, target, metadata)
values (
    '22222222-2222-4222-8222-222222222222',
    '11111111-1111-4111-8111-111111111111',
    'system',
    'seed.loaded',
    null,
    '{"note": "local development seed"}'::jsonb
);
