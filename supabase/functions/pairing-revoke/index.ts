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
 * Revokes a paired device (PLAN §4.4 `pairing-revoke`).
 *
 * Flipping `devices.status` alone already cuts the device's read access —
 * every RLS policy that serves a child scopes on `status = 'active'`. The
 * audit row is written here so the parent's history shows who revoked what.
 */

Deno.serve(async (req) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;

  try {
    const body = await req.json().catch(() => null);
    const deviceId: unknown = body?.device_id;
    if (typeof deviceId !== "string") {
      return json({ error: "invalid_request" }, 400);
    }

    const caller = await callerId(req);
    if (!caller) return json({ error: "unauthorized" }, 401);

    const allowed = await callAsUser<boolean>(req, "is_parent_of_device", {
      p_device_id: deviceId,
    });
    if (allowed !== true) return json({ error: "forbidden" }, 403);

    const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

    const { data: device } = await admin
      .from("devices")
      .select("id, child_id, device_name, status")
      .eq("id", deviceId)
      .maybeSingle();
    if (!device) return json({ error: "not_found" }, 404);

    const { error: updErr } = await admin
      .from("devices")
      .update({ status: "revoked" })
      .eq("id", deviceId);
    if (updErr) return json({ error: updErr.message }, 500);

    const { data: child } = await admin
      .from("children")
      .select("family_id")
      .eq("id", device.child_id)
      .maybeSingle();
    if (child) {
      await admin.from("audit_log").insert({
        family_id: child.family_id,
        actor_id: caller,
        actor_type: "parent",
        action: "device.revoked",
        target: deviceId,
        metadata: { device_name: device.device_name },
      });
    }

    return json({ ok: true });
  } catch (e) {
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
