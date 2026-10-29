import { createClient } from "npm:@supabase/supabase-js@2";
import {
  handleOptions,
  json,
  PAIRING_SALT,
  SERVICE_ROLE_KEY,
  sha256Hex,
  SUPABASE_URL,
} from "../_shared/util.ts";

/**
 * Exchanges a pairing code for a **device-scoped session** (PLAN §4.4
 * `pairing-verify`, C1).
 *
 * The child device never signs up for an account and never sees a parent
 * token: on success we mint a dedicated auth user for this device, which is
 * the `devices.user_id` the RLS policies key off. Nothing here trusts the
 * caller beyond the code itself — the code *is* the credential, so it is
 * attempt-limited, time-limited and stored only as a hash.
 *
 * Two clients on purpose: signing in swaps a supabase-js client's
 * `Authorization` header to the new session, and the fresh device user is
 * *not* allowed to insert its own `devices` row. Keeping auth calls on
 * `authClient` leaves `db` permanently on the service role.
 */

const MAX_ATTEMPTS = 5;

/** Crockford-normalise what a human typed: strip separators, fix lookalikes. */
function normalize(input: string): string {
  return input
    .toUpperCase()
    .replace(/[^0-9A-Z]/g, "")
    .replace(/[IL]/g, "1")
    .replace(/O/g, "0");
}

Deno.serve(async (req) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;

  try {
    const body = await req.json().catch(() => null);
    const rawCode: unknown = body?.code;
    const device = body?.device ?? {};
    if (typeof rawCode !== "string" || normalize(rawCode).length === 0) {
      return json({ error: "invalid_request" }, 400);
    }

    const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);
    const authClient = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

    const code = normalize(rawCode);
    const codeHash = await sha256Hex(code + PAIRING_SALT);

    const { data: row } = await db
      .from("pairing_codes")
      .select("*")
      .eq("code_hash", codeHash)
      .maybeSingle();

    if (!row) return json({ error: "invalid_code" }, 404);
    if (row.used_at) return json({ error: "code_used" }, 410);
    if (new Date(row.expires_at).getTime() < Date.now()) {
      return json({ error: "code_expired" }, 410);
    }

    const attempts = Math.floor(row.attempts ?? 0);
    if (attempts >= MAX_ATTEMPTS) {
      return json({ error: "too_many_attempts" }, 429);
    }

    // Count the attempt before doing any work so a failing loop cannot race.
    const { data: bumped } = await db
      .from("pairing_codes")
      .update({ attempts: attempts + 1 })
      .eq("id", row.id)
      .eq("attempts", attempts)
      .select("attempts")
      .maybeSingle();
    if (!bumped) return json({ error: "too_many_attempts" }, 429);

    const childId: string | null = row.child_id;
    if (!childId) return json({ error: "invalid_code" }, 404);

    // Claim the code with a compare-and-set so two devices cannot both win.
    const now = new Date().toISOString();
    const { data: claimed } = await db
      .from("pairing_codes")
      .update({ used_at: now })
      .eq("id", row.id)
      .is("used_at", null)
      .select("id")
      .maybeSingle();
    if (!claimed) return json({ error: "code_used" }, 410);

    // --- mint a dedicated device user -------------------------------------
    const email = `device-${crypto.randomUUID()}@devices.familyguard.local`;
    const password = crypto.randomUUID() + "-Pw1";
    const { data: created, error: createErr } =
      await authClient.auth.admin.createUser({
        email,
        password,
        email_confirm: true,
        app_metadata: { role: "device" },
        user_metadata: {
          device_name: typeof device.name === "string" ? device.name : "Device",
        },
      });
    if (createErr || !created.user) {
      return json({ error: createErr?.message ?? "user_create_failed" }, 500);
    }

    // --- register the hardware (service role, before any sign-in) ---------
    const { data: deviceRow, error: deviceErr } = await db
      .from("devices")
      .insert({
        child_id: childId,
        user_id: created.user.id,
        device_name: typeof device.name === "string" ? device.name : "Device",
        model: typeof device.model === "string" ? device.model : null,
        android_sdk: typeof device.sdk === "number" ? device.sdk : null,
        app_version:
          typeof device.app_version === "string" ? device.app_version : null,
        status: "active",
        paired_at: now,
        last_seen_at: now,
      })
      .select("id")
      .single();
    if (deviceErr || !deviceRow) {
      return json({ error: deviceErr?.message ?? "device_failed" }, 500);
    }

    const { data: child } = await db
      .from("children")
      .select("family_id")
      .eq("id", childId)
      .maybeSingle();
    if (child) {
      await db.from("audit_log").insert({
        family_id: child.family_id,
        actor_id: created.user.id,
        actor_type: "device",
        action: "device.paired",
        target: deviceRow.id,
        metadata: { device_name: device.name ?? null },
      });
    }

    // --- last: mint the device session ------------------------------------
    const { data: session, error: sessionErr } =
      await authClient.auth.signInWithPassword({ email, password });
    if (sessionErr || !session.session) {
      return json({ error: sessionErr?.message ?? "session_failed" }, 500);
    }

    return json({
      child_id: childId,
      device_id: deviceRow.id,
      access_token: session.session.access_token,
      refresh_token: session.session.refresh_token,
      expires_in: session.session.expires_in,
      token_type: session.session.token_type ?? "bearer",
    });
  } catch (e) {
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
