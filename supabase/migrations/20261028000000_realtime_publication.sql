-- Enable postgres_changes for every table SyncEngine subscribes to.
--
-- Supabase only streams tables that are members of the `supabase_realtime`
-- publication. Without this the client's channel join is rejected with:
--   "Unable to subscribe to changes with given parameters. Please check
--    Realtime is enabled for the given connect parameters"
-- which leaves the app permanently on "Sync: reconnecting…".
--
-- REPLICA IDENTITY FULL is required because the app filters on `child_id`,
-- which is not the primary key. With the default identity (the PK only) an
-- UPDATE/DELETE payload would not carry `child_id`, so the filter could never
-- match and those events would be dropped silently.
--
-- Idempotent: safe to run repeatedly.

do $$
declare t text;
begin
  foreach t in array array['app_limits', 'schedules', 'web_filters', 'time_requests'] loop
    if not exists (
      select 1
        from pg_publication_tables
       where pubname = 'supabase_realtime'
         and schemaname = 'public'
         and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;

    execute format('alter table public.%I replica identity full', t);
  end loop;
end $$;
