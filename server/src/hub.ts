// One Durable Object per user. Holds that user's live WebSocket(s), presence subscribers,
// and relays realtime frames (messages, ticks, typing, call signalling).
import { DurableObject } from "cloudflare:workers";
import { Env, now } from "./lib";
import {
  hub, sendMessage, markDelivered, markRead, deleteForEveryone, deleteForMe, relayTyping,
  handleCall, blockState, clearChat,
} from "./core";

const SUB_TTL = 3 * 60_000;

export class Hub extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // keep-alive pings answered without waking the object
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  private sockets(): WebSocket[] {
    return this.ctx.getWebSockets().filter((s) => s.readyState === WebSocket.OPEN);
  }

  async fetch(req: Request): Promise<Response> {
    const userId = req.headers.get("x-user");
    if (!userId || req.headers.get("Upgrade") !== "websocket") return new Response("bad", { status: 400 });
    const wasOnline = this.sockets().length > 0;
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    pair[1].serializeAttachment({ u: userId });
    await this.ctx.storage.put("uid", userId);
    pair[1].send(JSON.stringify({ t: "hello", at: now() }));
    if (!wasOnline) this.ctx.waitUntil(this.broadcastPresence(userId, true, 0));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer) {
    if (typeof data !== "string") return;
    const me: string = (ws.deserializeAttachment() as any)?.u;
    if (!me) return;
    let f: any;
    try { f = JSON.parse(data); } catch { return; }
    const env = this.env;
    try {
      switch (f.t) {
        case "msg": {
          const r = await sendMessage(env, me, f.m);
          ws.send(JSON.stringify(r.ok ? { t: "ack", id: f.m.id, m: r.msg } : { t: "err", id: f.m?.id, e: r.error }));
          break;
        }
        case "delivered": await markDelivered(env, me, f.ids); break;
        case "read": await markRead(env, me, f.peer, f.upTo); break;
        case "del_all": {
          const ok = await deleteForEveryone(env, me, f.id);
          if (!ok) ws.send(JSON.stringify({ t: "err", id: f.id, e: "cannot delete" }));
          break;
        }
        case "del_me": await deleteForMe(env, me, f.ids); break;
        case "clear": await clearChat(env, me, f.peer); break;
        case "typing": await relayTyping(env, me, f.to, f.s); break;
        case "call": await handleCall(env, me, f); break;
        case "sub": {
          const users: string[] = (Array.isArray(f.users) ? f.users : []).slice(0, 50);
          for (const u of users) {
            if (u === me) continue;
            const bs = await blockState(env, me, u);
            if (bs.blockedMe || bs.iBlocked) {
              ws.send(JSON.stringify({ t: "presence", u, online: false, lastSeen: 0 }));
              continue;
            }
            const p = await hub(env, u).subscribe(me, u);
            ws.send(JSON.stringify({ t: "presence", u, ...p }));
          }
          break;
        }
      }
    } catch (e: any) {
      ws.send(JSON.stringify({ t: "err", e: String(e?.message || e) }));
    }
  }

  async webSocketClose(ws: WebSocket, code: number) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch {}
    await this.onGone(ws);
  }
  async webSocketError(ws: WebSocket) {
    await this.onGone(ws);
  }
  private async onGone(ws: WebSocket) {
    const me: string = (ws.deserializeAttachment() as any)?.u;
    if (!me) return;
    if (this.sockets().filter((s) => s !== ws).length === 0) {
      const t = now();
      await this.env.DB.prepare("UPDATE users SET last_seen=?1 WHERE id=?2").bind(t, me).run();
      await this.broadcastPresence(me, false, t);
    }
  }

  private async broadcastPresence(me: string, online: boolean, lastSeen: number) {
    const subs = await this.ctx.storage.list<number>({ prefix: "sub:" });
    const t = now();
    const tasks: Promise<any>[] = [];
    for (const [key, exp] of subs) {
      const u = key.slice(4);
      if (exp < t) { tasks.push(this.ctx.storage.delete(key)); continue; }
      tasks.push(hub(this.env, u).push({ t: "presence", u: me, online, lastSeen }));
    }
    await Promise.allSettled(tasks);
  }

  // ---------------- RPC ----------------
  /** Deliver a frame to this user's live sockets. Returns number of sockets reached. */
  async push(frame: any): Promise<number> {
    const socks = this.sockets();
    const s = JSON.stringify(frame);
    let n = 0;
    for (const ws of socks) { try { ws.send(s); n++; } catch {} }
    return n;
  }

  /** `subscriber` wants presence updates about this user. */
  async subscribe(subscriber: string, selfId: string): Promise<{ online: boolean; lastSeen: number }> {
    await this.ctx.storage.put("sub:" + subscriber, now() + SUB_TTL);
    const online = this.sockets().length > 0;
    let lastSeen = 0;
    if (!online) {
      const r = await this.env.DB.prepare("SELECT last_seen FROM users WHERE id=?1").bind(selfId).first<{ last_seen: number }>();
      lastSeen = r?.last_seen || 0;
    }
    return { online, lastSeen };
  }

  async isOnline(): Promise<boolean> {
    return this.sockets().length > 0;
  }
}
