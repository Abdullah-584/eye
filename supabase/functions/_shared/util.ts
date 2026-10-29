/**
 * Shared helpers for FamilyGuard's Edge Functions.
 *
 * Secrets (set with `supabase secrets set --env-file ...`):
 *   SERVICE_ROLE_KEY  server-only key; bypasses RLS. Never shipped to clients.
 *   PAIRING_SALT      pepper for the pairing-code hash.
 *
 * `SUPABASE_URL` is injected by the platform; the literal fallback is the
 * project's public URL, which is not a secret.
 */

const PROJECT_REF = "uqdolyqcxvxsndhzfkwx";

export const SUPABASE_URL =
  Deno.env.get("SUPABASE_URL") ?? `https://${PROJECT_REF}.supabase.co`;
export const SERVICE_ROLE_KEY = Deno.env.get("SERVICE_ROLE_KEY") ?? "";
export const PAIRING_SALT = Deno.env.get("PAIRING_SALT") ?? "";

export const corsHeaders: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

/** Every response is JSON with CORS so a browser client can call us too. */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

/** Returns a preflight response for OPTIONS, else null so the caller continues. */
export function handleOptions(req: Request): Response | null {
  return req.method === "OPTIONS"
    ? new Response("ok", { headers: corsHeaders })
    : null;
}

/**
 * The calling user's id, verified against GoTrue — never read from an
 * unverified JWT payload. Returns null when the token is missing/expired.
 */
export async function callerId(req: Request): Promise<string | null> {
  const jwt = req.headers.get("Authorization") ?? "";
  if (!jwt) return null;
  try {
    const res = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
      headers: { apikey: SERVICE_ROLE_KEY, Authorization: jwt },
    });
    if (!res.ok) return null;
    const user = await res.json();
    return typeof user?.id === "string" ? user.id : null;
  } catch {
    return null;
  }
}

/**
 * Runs a `SECURITY DEFINER` helper *as the caller*: we forward the caller's
 * own JWT, so `auth.uid()` inside the function is theirs. RLS-style checks
 * must never be done from data we control.
 */
export async function callAsUser<T>(
  req: Request,
  fn: string,
  args: Record<string, unknown>,
): Promise<T | null> {
  const jwt = req.headers.get("Authorization") ?? "";
  if (!jwt) return null;
  try {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
      method: "POST",
      headers: {
        apikey: SERVICE_ROLE_KEY,
        Authorization: jwt,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(args),
    });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

/** sha256 as lowercase hex — used to store/compare pairing codes. */
export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
