import { createClient } from "npm:@supabase/supabase-js@2";
import {
  callAsUser,
  callerId,
  handleOptions,
  json,
  PAIRING_SALT,
  SERVICE_ROLE_KEY,
  sha256Hex,
  SUPABASE_URL,
} from "../_shared/util.ts";

/**
 * Issues a short pairing code (PLAN §4.4 `pairing-create`).
 *
 * Only the **hash** is stored — `pairing_codes` denies all client reads, so a
 * compromised child device can never harvest other families' codes. The plain
 * code is returned exactly once, to the parent's screen, for the QR/6-digit
 * entry on the child device.
 */

/**
 * Digits only: the child's join field is `KeyboardType.NumberPassword` and
 * filters to digits (PLAN C1, `join_code` = "6-digit code"), so a base32 code
 * could never be typed in — the two ends must agree on the alphabet.
 * normalize() on the verify side still applies Crockford lookalike fixes,
 * so 0/O and 1/I remain interchangeable for a retyped code.
 */
const ALPHABET = "0123456789";
const CODE_LENGTH = 6;
const TTL_MINUTES = 5;
const MAX_PER_HOUR = 5;

Deno.serve(async (req) => {
  const preflight = handleOptions(req);
  if (preflight) return preflight;

  try {
    const body = await req.json().catch(() => null);
    const childId: unknown = body?.child_id;
    if (typeof childId !== "string") {
      return json({ error: "invalid_request" }, 400);
    }

    const caller = await callerId(req);
    if (!caller) return json({ error: "unauthorized" }, 401);

    const allowed = await callAsUser<boolean>(req, "is_parent_of_child", {
      p_child_id: childId,
    });
    if (allowed !== true) return json({ error: "forbidden" }, 403);

    const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

    const { data: child } = await admin
      .from("children")
      .select("id, family_id")
      .eq("id", childId)
      .maybeSingle();
    if (!child) return json({ error: "not_found" }, 404);

    // In-DB rate limit: at most 5 codes per family per hour.
    const hourAgo = new Date(Date.now() - 3_600_000).toISOString();
    const { count } = await admin
      .from("pairing_codes")
      .select("id", { count: "exact", head: true })
      .eq("family_id", child.family_id)
      .gte("created_at", hourAgo);
    if ((count ?? 0) >= MAX_PER_HOUR) {
      return json({ error: "rate_limited" }, 429);
    }

    const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
    let code = "";
    for (let i = 0; i < CODE_LENGTH; i++) {
      code += ALPHABET[bytes[i] % ALPHABET.length];
    }

    const expiresAt = new Date(Date.now() + TTL_MINUTES * 60_000).toISOString();

    // One live code per family: retire anything still open first.
    await admin
      .from("pairing_codes")
      .update({ used_at: new Date().toISOString() })
      .eq("family_id", child.family_id)
      .is("used_at", null);

    const { error: insertErr } = await admin.from("pairing_codes").insert({
      family_id: child.family_id,
      child_id: child.id,
      code_hash: await sha256Hex(code + PAIRING_SALT),
      code_hint: code.slice(0, 2) + "****",
      created_by: caller,
      expires_at: expiresAt,
    });
    if (insertErr) return json({ error: insertErr.message }, 500);

    return json({ code, expires_at: expiresAt });
  } catch (e) {
    return json({ error: String((e as Error)?.message ?? e) }, 500);
  }
});
