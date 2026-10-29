import { createClient } from "npm:@supabase/supabase-js@2";
import {
  callerId,
  handleOptions,
  json,
  SERVICE_ROLE_KEY,
  SUPABASE_URL,
} from "../_shared/util.ts";

/**
 * Lightweight liveness ping from a child device (PLAN §4.4 `device-heartbeat`).
 *
 * Only ever touches the row owned by the caller: `devices.user_id` is the
 * device's own auth user, so a device cannot heartbeat — or fake liveness
 * for — anyone else's hardware.
 */

Deno.serve(async (req) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;

  try {
    const caller = await callerId(req);
    if (!caller) return json({ error: "unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const patch: Record<string, unknown> = { last_seen_at: new Date().toISOString() };
    if (typeof body?.app_version === "string") patch.app_version = body.app_version;
    if (typeof body?.sdk === "number") patch.android_sdk = body.sdk;
    if (typeof body?.fcm_token === "string") patch.fcm_token = body.fcm_token;

    const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);
    const { error } = await admin
      .from("devices")
      .update(patch)
      .eq("user_id", caller);
    if (error) return json({ error: error.message }, 500);

    return json({ ok: true });
  } catch (e) {
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
