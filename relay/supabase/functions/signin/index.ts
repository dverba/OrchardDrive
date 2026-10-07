// QR sign-in relay for AAOSMusic.
//
// The car can't run Apple's sign-in (no Apple Music app on AAOS), so the user signs in on their
// phone instead:
//
//   car    POST /signin/start    {developer_token}        -> {session_id, code, expires_at}
//   car    shows a QR for  <PAGE_URL>?c=<code>
//   phone  GET  /signin/session/<code>                     -> {developer_token}  (page runs MusicKit JS)
//   phone  POST /signin/complete  {code, music_user_token} -> {ok}
//   car    POST /signin/poll      {session_id}             -> {status: pending|complete, music_user_token?}
//
// Also:
//   app    GET  /signin/developer-token                   -> {token, expires_at}  (signed here, so apps ship no Apple key)
//   app    POST /signin/report  {app_version, device, report} -> {ok}            (in-car debug reports; no adb in the car)
//
// /start no longer needs developer_token when the Apple key secrets are configured (APPLE_TEAM_ID,
// APPLE_KEY_ID, APPLE_PRIVATE_KEY = the .p8 contents). Old clients that send one still work.
//
// session_id is a random UUID known only to the car; code is ~50 bits of entropy and valid for 5 minutes.
// Rows are deleted as soon as the car collects the token, and expired rows are purged on every start.
// verify_jwt is off: neither the car nor the phone has a Supabase user. The secrets above are the auth.

import { createClient } from "npm:@supabase/supabase-js@2";

const PAGE_ORIGINS = ["https://dverba.github.io", "http://localhost:8765"];
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O/1/I/L
const CODE_LENGTH = 10;
const MAX_TOKEN_LENGTH = 4096;
const MAX_REPORT_LENGTH = 256 * 1024;
const STARTS_PER_CLIENT_PER_MINUTE = 6;
const REPORTS_PER_CLIENT_PER_HOUR = 20;
const DEVELOPER_TOKEN_DAYS = 7;

function serviceKey(): string {
  const keys = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (keys) {
    try {
      return JSON.parse(keys)["default"];
    } catch { /* fall through */ }
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
}

const db = createClient(Deno.env.get("SUPABASE_URL")!, serviceKey(), { auth: { persistSession: false } });

function cors(req: Request): Record<string, string> {
  const origin = req.headers.get("origin") ?? "";
  return PAGE_ORIGINS.includes(origin)
    ? {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "content-type",
      "Vary": "Origin",
    }
    : {};
}

function json(req: Request, body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors(req), "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Salted hash of the caller's IP: enough to rate-limit, not enough to identify anyone. */
function clientHash(req: Request): Promise<string> {
  const ip = (req.headers.get("x-forwarded-for") ?? "").split(",")[0].trim() || "unknown";
  return sha256Hex(ip + "|" + Deno.env.get("SUPABASE_URL"));
}

async function recentCount(table: string, client: string, sinceMs: number): Promise<number> {
  const { count } = await db.from(table)
    .select("id", { count: "exact", head: true })
    .eq("client_hash", client)
    .gt("created_at", new Date(Date.now() - sinceMs).toISOString());
  return count ?? 0;
}

// ---- Developer token (ES256 JWT signed with the MusicKit key from Supabase secrets) ----

let cachedDeveloperToken: { token: string; expiresAt: number } | null = null;

const b64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function developerToken(): Promise<{ token: string; expiresAt: number } | null> {
  // Reuse a token until it has less than a day left.
  if (cachedDeveloperToken && cachedDeveloperToken.expiresAt - Date.now() > 86_400_000) return cachedDeveloperToken;
  const teamId = Deno.env.get("APPLE_TEAM_ID");
  const keyId = Deno.env.get("APPLE_KEY_ID");
  const pem = Deno.env.get("APPLE_PRIVATE_KEY");
  if (!teamId || !keyId || !pem) return null;

  const der = Uint8Array.from(atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const iat = Math.floor(Date.now() / 1000);
  const exp = iat + DEVELOPER_TOKEN_DAYS * 86_400;
  const enc = new TextEncoder();
  const header = b64url(enc.encode(JSON.stringify({ alg: "ES256", kid: keyId })));
  const payload = b64url(enc.encode(JSON.stringify({ iss: teamId, iat, exp })));
  // WebCrypto ECDSA signatures are already raw r||s, which is what JWS ES256 wants.
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, enc.encode(`${header}.${payload}`)));
  cachedDeveloperToken = { token: `${header}.${payload}.${b64url(sig)}`, expiresAt: exp * 1000 };
  return cachedDeveloperToken;
}

async function getDeveloperToken(req: Request): Promise<Response> {
  const t = await developerToken();
  if (!t) return json(req, { error: "developer token not configured" }, 503);
  return json(req, { token: t.token, expires_at: new Date(t.expiresAt).toISOString() });
}

async function report(req: Request): Promise<Response> {
  const client = await clientHash(req);
  if (await recentCount("debug_reports", client, 3_600_000) >= REPORTS_PER_CLIENT_PER_HOUR) {
    return json(req, { error: "too many reports" }, 429);
  }
  const body = await req.json().catch(() => ({}));
  if (typeof body.report !== "string" || !body.report || body.report.length > MAX_REPORT_LENGTH) {
    return json(req, { error: "report required (max 256 KB)" }, 400);
  }
  const short = (v: unknown) => typeof v === "string" ? v.slice(0, 200) : null;
  const { data, error } = await db.from("debug_reports")
    .insert({ client_hash: client, app_version: short(body.app_version), device: short(body.device), report: body.report })
    .select("id")
    .single();
  if (error) {
    console.error("report failed", error.message);
    return json(req, { error: "could not store report" }, 500);
  }
  return json(req, { ok: true, id: data.id });
}

function newCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

function normalizeCode(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

const looksLikeJwt = (t: unknown): t is string =>
  typeof t === "string" && t.length < MAX_TOKEN_LENGTH && /^[\w-]+\.[\w-]+\.[\w-]+$/.test(t);

async function start(req: Request): Promise<Response> {
  const body = await req.json().catch(() => ({}));
  const developer = looksLikeJwt(body.developer_token) ? body.developer_token : (await developerToken())?.token;
  if (!developer) return json(req, { error: "developer_token required" }, 400);

  const client = await clientHash(req);
  if (await recentCount("signin_sessions", client, 60_000) >= STARTS_PER_CLIENT_PER_MINUTE) {
    return json(req, { error: "too many sign-in attempts, wait a minute" }, 429);
  }

  // Opportunistic cleanup keeps the table tiny without a cron job.
  await db.from("signin_sessions").delete().lt("expires_at", new Date().toISOString());

  for (let attempt = 0; attempt < 3; attempt++) {
    const { data, error } = await db.from("signin_sessions")
      .insert({ code: newCode(), developer_token: developer, client_hash: client })
      .select("id, code, expires_at")
      .single();
    if (!error) return json(req, { session_id: data.id, code: data.code, expires_at: data.expires_at });
    if (error.code !== "23505") { // anything but a code collision
      console.error("start failed", error.message);
      return json(req, { error: "could not create session" }, 500);
    }
  }
  return json(req, { error: "could not create session" }, 500);
}

async function session(req: Request, rawCode: string): Promise<Response> {
  const { data } = await db.from("signin_sessions")
    .select("developer_token, user_token, expires_at")
    .eq("code", normalizeCode(rawCode))
    .maybeSingle();
  if (!data || new Date(data.expires_at) < new Date()) return json(req, { error: "expired" }, 404);
  if (data.user_token) return json(req, { status: "complete" });
  return json(req, { status: "pending", developer_token: data.developer_token, expires_at: data.expires_at });
}

async function complete(req: Request): Promise<Response> {
  const body = await req.json().catch(() => ({}));
  const token = body.music_user_token;
  if (typeof body.code !== "string" || typeof token !== "string" || !token || token.length > MAX_TOKEN_LENGTH) {
    return json(req, { error: "code and music_user_token required" }, 400);
  }
  // Only a pending, unexpired session can be completed, and only once.
  const { data, error } = await db.from("signin_sessions")
    .update({ user_token: token, completed_at: new Date().toISOString() })
    .eq("code", normalizeCode(body.code))
    .is("user_token", null)
    .gt("expires_at", new Date().toISOString())
    .select("id");
  if (error) {
    console.error("complete failed", error.message);
    return json(req, { error: "could not complete" }, 500);
  }
  if (!data?.length) return json(req, { error: "expired or already used" }, 404);
  return json(req, { ok: true });
}

async function poll(req: Request): Promise<Response> {
  const body = await req.json().catch(() => ({}));
  if (typeof body.session_id !== "string" || !/^[0-9a-f-]{36}$/i.test(body.session_id)) {
    return json(req, { error: "session_id required" }, 400);
  }
  const { data } = await db.from("signin_sessions")
    .select("user_token, expires_at")
    .eq("id", body.session_id)
    .maybeSingle();
  if (!data) return json(req, { status: "expired" }, 410);
  if (data.user_token) {
    // Hand the token over exactly once, then forget it.
    await db.from("signin_sessions").delete().eq("id", body.session_id);
    return json(req, { status: "complete", music_user_token: data.user_token });
  }
  if (new Date(data.expires_at) < new Date()) {
    await db.from("signin_sessions").delete().eq("id", body.session_id);
    return json(req, { status: "expired" }, 410);
  }
  return json(req, { status: "pending" });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(req) });
  const parts = new URL(req.url).pathname.split("/").filter(Boolean); // [..., "signin", route, arg?]
  const i = parts.lastIndexOf("signin");
  const route = parts[i + 1] ?? "";
  try {
    if (req.method === "POST" && route === "start") return await start(req);
    if (req.method === "GET" && route === "session" && parts[i + 2]) return await session(req, parts[i + 2]);
    if (req.method === "POST" && route === "complete") return await complete(req);
    if (req.method === "POST" && route === "poll") return await poll(req);
    if (req.method === "GET" && route === "developer-token") return await getDeveloperToken(req);
    if (req.method === "POST" && route === "report") return await report(req);
    return json(req, { error: "not found" }, 404);
  } catch (e) {
    console.error("unhandled", e instanceof Error ? e.message : e);
    return json(req, { error: "internal error" }, 500);
  }
});
