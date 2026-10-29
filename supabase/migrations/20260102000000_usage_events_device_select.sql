-- Batch upload of usage_events is an *upsert* (PLAN §4.6 idempotency key:
-- device_id, package_name, started_at, event_type). PostgreSQL has to read the
-- arbiter index through the RLS SELECT policies to detect a conflict, and the
-- device role previously had INSERT-only access — so every retry of a batch
-- that had already landed failed with 42501 instead of being ignored, and the
-- child's usage never reached the parent's reports at all.
--
-- Scope is exactly the rows the device itself wrote, so this grants nothing
-- new beyond what the insert policy already asserts.

create policy "usage_events: device reads own"
  on public.usage_events
  for select
  using (
    public.is_authenticated_user()
    and device_id = public.my_device_id()
  );
