// Shared helpers: auth tokens, Google ID token verification, FCM push, Cloudinary signing
export interface Env {
  DB: D1Database;
  HUB: DurableObjectNamespace<import("./hub").Hub>;
  GOOGLE_CLIENT_ID: string;
  CLOUDINARY_CLOUD: string;
  CLOUDINARY_API_KEY: string;
  CLOUDINARY_API_SECRET: string;
  SESSION_SECRET: string;
  TURN_URL: string;
  TURN_USER: string;
  TURN_PASS: string;
  FCM_SA: string; // service-account JSON
}

const enc = new TextEncoder();

export const now = () => Date.now();

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
export const err = (msg: string, status = 400) => json({ error: msg }, status);

export function b64url(buf: ArrayBuffer | Uint8Array): string {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
export function b64urlDecode(s: string): Uint8Array {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function newId(): string {
  const b = new Uint8Array(12);
  crypto.getRandomValues(b);
  return b64url(b);
}

// ---------- session tokens (stateless HMAC) ----------
let hmacKey: CryptoKey | null = null;
async function getHmacKey(env: Env) {
  if (!hmacKey)
    hmacKey = await crypto.subtle.importKey("raw", enc.encode(env.SESSION_SECRET),
      { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
  return hmacKey;
}
export async function makeToken(env: Env, userId: string): Promise<string> {
  const payload = `${userId}.${now()}`;
  const sig = await crypto.subtle.sign("HMAC", await getHmacKey(env), enc.encode(payload));
  return `v1.${payload}.${b64url(sig)}`;
}
export async function verifyToken(env: Env, token: string | null): Promise<string | null> {
  if (!token) return null;
  const p = token.split(".");
  if (p.length !== 4 || p[0] !== "v1") return null;
  const ok = await crypto.subtle.verify("HMAC", await getHmacKey(env), b64urlDecode(p[3]),
    enc.encode(`${p[1]}.${p[2]}`));
  return ok ? p[1] : null;
}

// ---------- Google ID token ----------
let googleKeys: { keys: any[]; exp: number } | null = null;
export async function verifyGoogleIdToken(env: Env, idToken: string) {
  const parts = idToken.split(".");
  if (parts.length !== 3) throw new Error("bad token");
  const header = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[0])));
  const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[1])));
  if (!googleKeys || googleKeys.exp < now()) {
    const r = await fetch("https://www.googleapis.com/oauth2/v3/certs");
    googleKeys = { keys: ((await r.json()) as any).keys, exp: now() + 3600_000 };
  }
  const jwk = googleKeys.keys.find((k) => k.kid === header.kid);
  if (!jwk) throw new Error("unknown key");
  const key = await crypto.subtle.importKey("jwk", jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlDecode(parts[2]),
    enc.encode(`${parts[0]}.${parts[1]}`));
  if (!ok) throw new Error("bad signature");
  if (!["accounts.google.com", "https://accounts.google.com"].includes(payload.iss)) throw new Error("bad iss");
  const auds = env.GOOGLE_CLIENT_ID.split(",").map((s) => s.trim());
  if (!auds.includes(payload.aud)) throw new Error("bad aud");
  if (payload.exp * 1000 < now()) throw new Error("expired");
  return payload as { sub: string; email?: string; name?: string; picture?: string };
}

// ---------- FCM HTTP v1 ----------
let fcmToken: { token: string; exp: number } | null = null;
async function getFcmAccessToken(env: Env): Promise<{ token: string; project: string }> {
  const sa = JSON.parse(env.FCM_SA);
  if (fcmToken && fcmToken.exp > now()) return { token: fcmToken.token, project: sa.project_id };
  const iat = Math.floor(now() / 1000);
  const head = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64url(enc.encode(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat, exp: iat + 3600,
  })));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${head}.${claim}`));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${head}.${claim}.${b64url(sig)}`,
  });
  const j: any = await r.json();
  fcmToken = { token: j.access_token, exp: now() + 50 * 60_000 };
  return { token: j.access_token, project: sa.project_id };
}

/** Send a data-only high priority push. Returns false if token is dead. */
export async function sendPush(env: Env, token: string, data: Record<string, string>, ttlSec = 2419200): Promise<boolean> {
  try {
    const { token: at, project } = await getFcmAccessToken(env);
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${project}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${at}`, "content-type": "application/json" },
      body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl: `${ttlSec}s` } } }),
    });
    if (r.status === 404 || r.status === 400) {
      const t = await r.text();
      if (t.includes("UNREGISTERED") || t.includes("not a valid FCM")) return false;
    }
    return true;
  } catch {
    return true;
  }
}

// ---------- Cloudinary ----------
export async function sha1Hex(s: string): Promise<string> {
  const h = await crypto.subtle.digest("SHA-1", enc.encode(s));
  return [...new Uint8Array(h)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
export async function cloudinarySign(env: Env, userId: string) {
  const timestamp = Math.floor(now() / 1000);
  const folder = `danav/${userId}`;
  const signature = await sha1Hex(`folder=${folder}&timestamp=${timestamp}${env.CLOUDINARY_API_SECRET}`);
  return { cloudName: env.CLOUDINARY_CLOUD, apiKey: env.CLOUDINARY_API_KEY, timestamp, folder, signature };
}

// ---------- row mapping ----------
export function msgOut(r: any) {
  return {
    id: r.id, sender: r.sender, receiver: r.receiver, type: r.type, body: r.body ?? null,
    media: r.media ?? null, replyTo: r.reply_to ?? null, createdAt: r.created_at,
    deliveredAt: r.delivered_at ?? null, readAt: r.read_at ?? null, updatedAt: r.updated_at,
  };
}
export function callOut(r: any) {
  return {
    id: r.id, caller: r.caller, callee: r.callee, video: !!r.video, status: r.status,
    createdAt: r.created_at, answeredAt: r.answered_at ?? null, endedAt: r.ended_at ?? null,
    updatedAt: r.updated_at,
  };
}
export function userOut(r: any, hidePrivate = false) {
  return {
    id: r.id, username: r.username, name: r.name,
    about: hidePrivate ? "" : r.about, avatar: hidePrivate ? null : r.avatar ?? null,
  };
}
