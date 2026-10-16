// Core chat / call logic shared by the WebSocket hub and HTTP fallback endpoints
import { Env, now, msgOut, sendPush } from "./lib";

export const hub = (env: Env, userId: string) => env.HUB.get(env.HUB.idFromName(userId));

const MAX_BODY = 8000;
const TYPES = new Set(["text", "image", "video", "audio", "file"]);

/** returns {iBlocked, blockedMe} between me and other */
export async function blockState(env: Env, me: string, other: string) {
  const { results } = await env.DB.prepare(
    "SELECT blocker FROM blocks WHERE (blocker=?1 AND blocked=?2) OR (blocker=?2 AND blocked=?1)"
  ).bind(me, other).all<{ blocker: string }>();
  return {
    iBlocked: results.some((r) => r.blocker === me),
    blockedMe: results.some((r) => r.blocker === other),
  };
}

async function userInfo(env: Env, ids: string[]) {
  const ph = ids.map((_, i) => `?${i + 1}`).join(",");
  const { results } = await env.DB.prepare(
    `SELECT id, username, name, avatar, fcm_token FROM users WHERE id IN (${ph})`
  ).bind(...ids).all<any>();
  const map: Record<string, any> = {};
  for (const r of results) map[r.id] = r;
  return map;
}

async function pushToToken(env: Env, userId: string, token: string | null, data: Record<string, string>, ttl?: number) {
  if (!token) return;
  const alive = await sendPush(env, token, data, ttl);
  if (!alive) await env.DB.prepare("UPDATE users SET fcm_token=NULL WHERE id=?1 AND fcm_token=?2").bind(userId, token).run();
}

function preview(m: { type: string; body?: string | null }) {
  switch (m.type) {
    case "image": return "📷 Photo" + (m.body ? ": " + m.body : "");
    case "video": return "🎥 Video" + (m.body ? ": " + m.body : "");
    case "audio": return "🎤 Voice message";
    case "file": return "📄 " + (m.body || "Document");
    default: return (m.body || "").slice(0, 300);
  }
}

export interface SendInput {
  id: string; to: string; type: string; body?: string | null; media?: string | null; replyTo?: string | null;
}

/** Store and deliver a message. Returns the stored row (as client shape) or an error string. */
export async function sendMessage(env: Env, me: string, m: SendInput): Promise<{ ok: true; msg: any } | { ok: false; error: string }> {
  if (!m || typeof m.id !== "string" || m.id.length < 8 || m.id.length > 64) return { ok: false, error: "bad id" };
  if (!m.to || m.to === me) return { ok: false, error: "bad recipient" };
  if (!TYPES.has(m.type)) return { ok: false, error: "bad type" };
  if (m.body && m.body.length > MAX_BODY) return { ok: false, error: "too long" };
  if (m.type === "text" && !m.body?.trim()) return { ok: false, error: "empty" };
  if (m.type !== "text" && !m.media) return { ok: false, error: "media missing" };

  const bs = await blockState(env, me, m.to);
  if (bs.iBlocked) return { ok: false, error: "you blocked this contact" };
  const t = now();
  const hidden = bs.blockedMe ? 1 : 0;
  const res = await env.DB.prepare(
    `INSERT INTO messages (id, sender, receiver, type, body, media, reply_to, created_at, hidden, updated_at)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?8) ON CONFLICT(id) DO NOTHING`
  ).bind(m.id, me, m.to, m.type, m.body ?? null, m.media ?? null, m.replyTo ?? null, t, hidden).run();

  // Idempotent retry: return existing row
  if (!res.meta.changes) {
    const row = await env.DB.prepare("SELECT * FROM messages WHERE id=?1 AND sender=?2").bind(m.id, me).first();
    if (!row) return { ok: false, error: "id conflict" };
    return { ok: true, msg: msgOut(row) };
  }
  const row = {
    id: m.id, sender: me, receiver: m.to, type: m.type, body: m.body ?? null, media: m.media ?? null,
    reply_to: m.replyTo ?? null, created_at: t, delivered_at: null, read_at: null, updated_at: t,
  };
  const out = msgOut(row);
  if (!hidden) {
    const info = await userInfo(env, [me, m.to]);
    const s = info[me];
    const sender = s ? { id: s.id, username: s.username, name: s.name, avatar: s.avatar } : null;
    const live = await hub(env, m.to).push({ t: "msg", m: out, u: sender });
    if (!live) {
      const r = info[m.to];
      await pushToToken(env, m.to, r?.fcm_token, {
        kind: "msg", id: m.id, from: me, name: s?.name || s?.username || "New message",
        avatar: s?.avatar || "", type: m.type, text: preview(m), createdAt: String(t),
        body: m.body ?? "", media: m.media ?? "", replyTo: m.replyTo ?? "",
      });
    }
  }
  return { ok: true, msg: out };
}

/** Receiver confirms delivery of message ids */
export async function markDelivered(env: Env, me: string, ids: string[]) {
  if (!Array.isArray(ids) || !ids.length) return;
  ids = ids.slice(0, 200);
  const t = now();
  const ph = ids.map((_, i) => `?${i + 3}`).join(",");
  const { results } = await env.DB.prepare(
    `UPDATE messages SET delivered_at=?1, updated_at=?1 WHERE receiver=?2 AND delivered_at IS NULL AND hidden=0 AND id IN (${ph}) RETURNING id, sender`
  ).bind(t, me, ...ids).all<{ id: string; sender: string }>();
  const bySender: Record<string, string[]> = {};
  for (const r of results) (bySender[r.sender] ||= []).push(r.id);
  await Promise.all(Object.entries(bySender).map(([s, list]) => hub(env, s).push({ t: "status", ids: list, d: t })));
}

/** Receiver read all messages from peer up to timestamp */
export async function markRead(env: Env, me: string, peer: string, upTo: number) {
  const t = now();
  const res = await env.DB.prepare(
    `UPDATE messages SET read_at=?1, delivered_at=COALESCE(delivered_at, ?1), updated_at=?1
     WHERE sender=?2 AND receiver=?3 AND read_at IS NULL AND hidden=0 AND created_at<=?4`
  ).bind(t, peer, me, upTo || t).run();
  if (res.meta.changes) await hub(env, peer).push({ t: "read", by: me, upTo: upTo || t, at: t });
}

export async function deleteForEveryone(env: Env, me: string, id: string) {
  const t = now();
  const row = await env.DB.prepare(
    `UPDATE messages SET type='deleted', body=NULL, media=NULL, reply_to=NULL, updated_at=?1
     WHERE id=?2 AND sender=?3 AND created_at > ?4 RETURNING receiver, hidden`
  ).bind(t, id, me, t - 2 * 24 * 3600_000).first<{ receiver: string; hidden: number }>();
  if (!row) return false;
  if (!row.hidden) await hub(env, row.receiver).push({ t: "deleted", id, at: t });
  await hub(env, me).push({ t: "deleted", id, at: t });
  return true;
}

export async function deleteForMe(env: Env, me: string, ids: string[]) {
  if (!Array.isArray(ids) || !ids.length) return;
  const stmts = ids.slice(0, 500).map((id) =>
    env.DB.prepare("INSERT OR IGNORE INTO deleted_for (user_id, message_id) VALUES (?1, ?2)").bind(me, id));
  await env.DB.batch(stmts);
}

export async function clearChat(env: Env, me: string, peer: string) {
  await env.DB.prepare(
    `INSERT OR IGNORE INTO deleted_for (user_id, message_id)
     SELECT ?1, id FROM messages WHERE (sender=?1 AND receiver=?2) OR (sender=?2 AND receiver=?1 AND hidden=0)`
  ).bind(me, peer).run();
}

export async function relayTyping(env: Env, me: string, to: string, s: string) {
  if (!to || to === me) return;
  const bs = await blockState(env, me, to);
  if (bs.iBlocked || bs.blockedMe) return;
  await hub(env, to).push({ t: "typing", u: me, s: s || "" });
}

/** Call signalling. f = {k, to, callId, video?, sdp?, sdpType?, c?} */
export async function handleCall(env: Env, me: string, f: any) {
  const { k, to, callId } = f || {};
  if (!k || !to || !callId || to === me) return;
  const t = now();
  const fwd: any = { t: "call", k, from: me, callId };
  for (const key of ["video", "sdp", "sdpType", "c", "on"]) if (f[key] !== undefined) fwd[key] = f[key];

  if (k === "invite") {
    const bs = await blockState(env, me, to);
    if (bs.iBlocked) return;
    await env.DB.prepare(
      "INSERT OR IGNORE INTO calls (id, caller, callee, video, status, created_at, updated_at) VALUES (?1,?2,?3,?4,'ringing',?5,?5)"
    ).bind(callId, me, to, f.video ? 1 : 0, t).run();
    if (bs.blockedMe) return; // caller just hears "calling…"
    const info = await userInfo(env, [me, to]);
    const s = info[me];
    fwd.name = s?.name || s?.username || "";
    fwd.avatar = s?.avatar || "";
    await hub(env, to).push(fwd);
    // Always push too: the app may be in background / killed
    await pushToToken(env, to, info[to]?.fcm_token, {
      kind: "call", callId, from: me, name: fwd.name, avatar: fwd.avatar, video: f.video ? "1" : "0",
      at: String(t),
    }, 40);
    return;
  }

  let sql: string | null = null;
  if (k === "accept") sql = "UPDATE calls SET status='answered', answered_at=?1, updated_at=?1 WHERE id=?2 AND callee=?3 AND status='ringing'";
  else if (k === "reject") sql = "UPDATE calls SET status='rejected', ended_at=?1, updated_at=?1 WHERE id=?2 AND callee=?3 AND status='ringing'";
  else if (k === "busy") sql = "UPDATE calls SET status='busy', ended_at=?1, updated_at=?1 WHERE id=?2 AND callee=?3 AND status='ringing'";
  else if (k === "end") sql = `UPDATE calls SET status=CASE WHEN status='ringing' THEN 'missed' WHEN status='answered' THEN 'ended' ELSE status END,
                               ended_at=COALESCE(ended_at, ?1), updated_at=?1 WHERE id=?2 AND (caller=?3 OR callee=?3)`;
  if (sql) await env.DB.prepare(sql).bind(t, callId, me).run();

  const live = await hub(env, to).push(fwd);
  if (k === "end" && !live) {
    const info = await userInfo(env, [to]);
    await pushToToken(env, to, info[to]?.fcm_token, { kind: "call_end", callId, from: me }, 60);
  }
}

export { preview };
