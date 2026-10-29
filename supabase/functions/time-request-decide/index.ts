import { createClient } from "npm:@supabase/supabase-js@2";
import {
  callAsUser,
  callerId,
  handleOptions,
  json,
  SERVICE_ROLE_KEY,
  SUPABASE_URL,
} from "../_shared/util.ts";

/**
 * Decides a pending time request (PLAN §4.4 `time-request-decide`).
 *
 * `status`, `granted_seconds`, `decided_by` and `decided_at` are forgeable
 * only here: RLS deliberately forbids either client from writing them
 * (PLAN §4.3.10), so an approval is only ever issued by this function after
 * it has confirmed the caller is a parent of the child that asked.
 *
 * Idempotent: a request that is already decided returns its current state
 * with 409, so a double-tap or a retried notification is harmless.
 */

/** Hard ceiling from the plan: a parent may never grant more than 4 hours. */
const MAX_GRANT_SECONDS = 4 * 3600;

Deno.serve(async (req) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;

  try {
    const body = await req.json().catch(() => null);
    const requestId: unknown = body?.request_id;
    const decision: unknown = body?.decision;
    if (
      typeof requestId !== "string" ||
      (decision !== "approve" && decision !== "deny")
    ) {
      return json({ error: "invalid_request" }, 400);
    }

    const caller = await callerId(req);
    if (!caller) return json({ error: "unauthorized" }, 401);

    const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

    const { data: row, error: readErr } = await admin
      .from("time_requests")
      .select("*")
      .eq("id", requestId)
      .maybeSingle();
    if (readErr) return json({ error: readErr.message }, 500);
    if (!row) return json({ error: "not_found" }, 404);

    // Authorization happens through the helper with the caller's JWT, so
    // auth.uid() inside it is the caller — not something we assert.
    const allowed = await callAsUser<boolean>(req, "is_parent_of_child", {
      p_child_id: row.child_id,
    });
    if (allowed !== true) return json({ error: "forbidden" }, 403);

    if (row.status !== "pending") {
      return json(
        { status: row.status, granted_seconds: row.granted_seconds },
        409,
      );
    }

    const requested = Math.floor(row.requested_seconds ?? 0);
    const granted =
      decision === "approve"
        ? Math.min(
            Math.max(
              Math.floor(body?.granted_seconds ?? requested),
              0,
            ),
            MAX_GRANT_SECONDS,
          )
        : null;

    // `eq("status","pending")` makes the update a compare-and-set: two racing
    // decisions can't both land.
    const { data: updated, error: updErr } = await admin
      .from("time_requests")
      .update({
        status: decision === "approve" ? "approved" : "denied",
        granted_seconds: granted,
        decided_by: caller,
        decided_at: new Date().toISOString(),
      })
      .eq("id", requestId)
      .eq("status", "pending")
      .select()
      .maybeSingle();
    if (updErr) return json({ error: updErr.message }, 500);
    if (!updated) {
      const { data: current } = await admin
        .from("time_requests")
        .select("status, granted_seconds")
        .eq("id", requestId)
        .maybeSingle();
      return json(
        { status: current?.status, granted_seconds: current?.granted_seconds },
        409,
      );
    }

    const { data: child } = await admin
      .from("children")
      .select("family_id")
      .eq("id", row.child_id)
      .maybeSingle();
    if (child) {
      await admin.from("audit_log").insert({
        family_id: child.family_id,
        actor_id: caller,
        actor_type: "parent",
        action: decision === "approve" ? "time.approved" : "time.denied",
        target: requestId,
        metadata: {
          requested_seconds: requested,
          granted_seconds: granted,
        },
      });
    }

    return json({
      status: updated.status,
      granted_seconds: updated.granted_seconds,
    });
  } catch (e) {
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
