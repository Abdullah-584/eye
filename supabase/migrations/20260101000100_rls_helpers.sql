-- ============================================================================
-- 20260101000100_rls_helpers.sql — SECURITY DEFINER helper functions
--
-- Why these exist: a policy that SELECTs from a table which itself has RLS
-- causes "infinite recursion detected in policy" errors in Postgres. Every
-- helper below is SECURITY DEFINER (owned by the migration role, which owns
-- the tables and therefore is not subject to RLS), so it can read
-- family_members/children/devices without re-entering their policies.
--
-- Rules:
--   * search_path is pinned — never a hijackable path.
--   * auth.uid() is NULL for anon and for service_role; all helpers therefore
--     return false for "nobody", which is the deny-by-default we want.
--   * Policies in 20260101000200_rls_policies.sql may only reference these
--     helpers plus auth.uid() and the row's own columns.
-- ============================================================================

-- Is the signed-in profile a parent/guardian of this family?
create or replace function public.is_parent_of_family(p_family_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.family_members fm
     where fm.family_id = p_family_id
       and fm.profile_id = auth.uid()
       and fm.role in ('parent', 'guardian')
  );
$$;

-- Is the signed-in profile any member of this family?
create or replace function public.is_member_of_family(p_family_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.family_members fm
     where fm.family_id = p_family_id
       and fm.profile_id = auth.uid()
  );
$$;

-- Did the signed-in profile create this family? Needed so the very first
-- family_members row can be inserted before anyone is a member yet.
create or replace function public.is_creator_of_family(p_family_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.families f
     where f.id = p_family_id
       and f.created_by = auth.uid()
  );
$$;

-- Do the signed-in profile and p_profile_id belong to the same family?
create or replace function public.shares_family_with(p_profile_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.family_members mine
      join public.family_members theirs
        on theirs.family_id = mine.family_id
       and theirs.profile_id = p_profile_id
     where mine.profile_id = auth.uid()
  );
$$;

-- Which family does the signed-in profile belong to? (first match)
create or replace function public.my_family_id()
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select fm.family_id
    from public.family_members fm
   where fm.profile_id = auth.uid()
   order by fm.created_at
   limit 1;
$$;

-- Is the signed-in profile a parent/guardian of this child?
create or replace function public.is_parent_of_child(p_child_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.children c
      join public.family_members fm
        on fm.family_id = c.family_id
       and fm.profile_id = auth.uid()
       and fm.role in ('parent', 'guardian')
     where c.id = p_child_id
  );
$$;

-- Which child does the signed-in *device* user serve? (null for parents)
create or replace function public.my_child_id()
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select d.child_id
    from public.devices d
   where d.user_id = auth.uid()
     and d.status = 'active'
   order by d.paired_at nulls last
   limit 1;
$$;

-- Which device row does the signed-in device user own?
create or replace function public.my_device_id()
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select d.id
    from public.devices d
   where d.user_id = auth.uid()
     and d.status = 'active'
   order by d.paired_at nulls last
   limit 1;
$$;

-- Is the signed-in device user the active device for this child?
create or replace function public.is_device_of_child(p_child_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.devices d
     where d.child_id = p_child_id
       and d.user_id = auth.uid()
       and d.status = 'active'
  );
$$;

-- Is the signed-in profile a parent of the child who owns this device?
create or replace function public.is_parent_of_device(p_device_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select exists (
    select 1
      from public.devices d
     where d.id = p_device_id
       and public.is_parent_of_child(d.child_id)
  );
$$;

-- Is the signed-in profile a parent of the child who owns this row?
-- Generic form for fact rows (usage_events, locations, time_requests).
create or replace function public.is_parent_of_child_row(p_child_id uuid)
returns boolean
language sql
stable
set search_path = public, pg_temp
as $$
  select public.is_parent_of_child(p_child_id);
$$;

-- The signed-in user is an authenticated Supabase session user.
-- Used by the write-side policies that must never accept `anon`.
create or replace function public.is_authenticated_user()
returns boolean
language sql
stable
set search_path = public, pg_temp
as $$
  select auth.uid() is not null;
$$;
