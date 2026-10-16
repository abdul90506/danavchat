// Danav Chat API — Cloudflare Worker
import {
  Env, json, err, now, newId, makeToken, verifyToken, verifyGoogleIdToken, cloudinarySign,
  msgOut, callOut, userOut, sendPush,
} from "./lib";
import { hub, sendMessage, markDelivered, markRead, deleteForEveryone, deleteForMe, clearChat, blockState, handleCall } from "./core";
export { Hub } from "./hub";

const USERNAME_RE = /^[a-z0-9_.]{3,20}$/;

async function body(req: Request): Promise<any> {
  try { return await req.json(); } catch { return {}; }
}

async function meRow(env: Env, id: string) {
  return env.DB.prepare("SELECT * FROM users WHERE id=?1").bind(id).first<any>();
}
function meOut(r: any) {
  return { id: r.id, email: r.email, username: r.username, name: r.name, about: r.about, avatar: r.avatar ?? null };
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    const method = req.method;

    try {
      if (path === "/" || path === "/health") return json({ ok: true, app: "Danav Chat", time: now() });

      // ---------- auth ----------
      if (path === "/auth/google" && method === "POST") {
        const { idToken } = await body(req);
        if (!idToken) return err("idToken required");
        let g;
        try { g = await verifyGoogleIdToken(env, idToken); } catch (e: any) { return err("Google verification failed: " + e.message, 401); }
        let u = await env.DB.prepare("SELECT * FROM users WHERE google_sub=?1").bind(g.sub).first<any>();
        if (!u) {
          const id = newId();
          const t = now();
          await env.DB.prepare(
            "INSERT INTO users (id, google_sub, email, name, avatar, created_at, updated_at) VALUES (?1,?2,?3,?4,?5,?6,?6)"
          ).bind(id, g.sub, g.email ?? null, (g.name || "").slice(0, 40), g.picture ?? null, t).run();
          u = await meRow(env, id);
        }
        return json({ token: await makeToken(env, u.id), user: meOut(u), needsUsername: !u.username });
      }

      // ---------- websocket ----------
      if (path === "/ws") {
        const uid = await verifyToken(env, url.searchParams.get("token"));
        if (!uid) return err("unauthorized", 401);
        if (req.headers.get("Upgrade") !== "websocket") return err("expected websocket", 426);
        const h = new Headers(req.headers);
        h.set("x-user", uid);
        return hub(env, uid).fetch(new Request(req.url, { headers: h }));
      }

      // everything below requires auth
      const auth = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? null;
      const me = await verifyToken(env, auth);
      if (!me) return err("unauthorized", 401);

      // ---------- profile ----------
      if (path === "/me" && method === "GET") {
        const u = await meRow(env, me);
        if (!u) return err("not found", 404);
        return json({ user: meOut(u), needsUsername: !u.username });
      }
      if (path === "/me" && method === "PUT") {
        const b = await body(req);
        const u = await meRow(env, me);
        if (!u) return err("not found", 404);
        let username = u.username;
        if (typeof b.username === "string" && b.username.toLowerCase() !== (u.username || "").toLowerCase()) {
          const un = b.username.trim().toLowerCase();
          if (!USERNAME_RE.test(un)) return err("Username: 3-20 chars, a-z 0-9 _ . only");
          const taken = await env.DB.prepare("SELECT id FROM users WHERE username=?1").bind(un).first();
          if (taken) return err("Username already taken", 409);
          username = un;
        }
        const name = typeof b.name === "string" ? b.name.trim().slice(0, 40) : u.name;
        const about = typeof b.about === "string" ? b.about.trim().slice(0, 140) : u.about;
        const avatar = b.avatar === null ? null : typeof b.avatar === "string" ? b.avatar.slice(0, 500) : u.avatar;
        if (!name) return err("Name required");
        await env.DB.prepare("UPDATE users SET username=?1, name=?2, about=?3, avatar=?4, updated_at=?5 WHERE id=?6")
          .bind(username, name, about, avatar, now(), me).run();
        return json({ user: meOut(await meRow(env, me)), needsUsername: !username });
      }
      if (path === "/username/check") {
        const un = (url.searchParams.get("u") || "").trim().toLowerCase();
        if (!USERNAME_RE.test(un)) return json({ available: false, reason: "3-20 chars: a-z 0-9 _ ." });
        const r = await env.DB.prepare("SELECT id FROM users WHERE username=?1").bind(un).first<any>();
        return json({ available: !r || r.id === me });
      }
      if (path === "/fcm" && method === "POST") {
        const { token } = await body(req);
        // a device token belongs to one account only
        await env.DB.batch([
          env.DB.prepare("UPDATE users SET fcm_token=NULL WHERE fcm_token=?1 AND id<>?2").bind(token ?? "", me),
          env.DB.prepare("UPDATE users SET fcm_token=?1 WHERE id=?2").bind(token || null, me),
        ]);
        return json({ ok: true });
      }
      if (path === "/logout" && method === "POST") {
        await env.DB.prepare("UPDATE users SET fcm_token=NULL WHERE id=?1").bind(me).run();
        return json({ ok: true });
      }

      // ---------- users ----------
      if (path === "/users/search") {
        const q = (url.searchParams.get("q") || "").trim().toLowerCase().replace(/^@/, "");
        if (q.length < 1) return json({ users: [] });
        const { results } = await env.DB.prepare(
          `SELECT u.id, u.username, u.name, u.about, u.avatar,
                  EXISTS(SELECT 1 FROM blocks b WHERE b.blocker=u.id AND b.blocked=?1) AS bm
           FROM users u WHERE u.id<>?1 AND u.username IS NOT NULL
             AND (u.username LIKE ?2 || '%' OR u.name LIKE '%' || ?2 || '%')
           ORDER BY (u.username = ?2) DESC, (u.username LIKE ?2 || '%') DESC, u.username LIMIT 30`
        ).bind(me, q).all<any>();
        return json({ users: results.map((r) => userOut(r, !!r.bm)) });
      }
      let m = path.match(/^\/users\/([\w-]+)$/);
      if (m && method === "GET") {
        const r = await env.DB.prepare("SELECT id, username, name, about, avatar FROM users WHERE id=?1").bind(m[1]).first<any>();
        if (!r) return err("not found", 404);
        const bs = await blockState(env, me, m[1]);
        return json({ user: userOut(r, bs.blockedMe), iBlocked: bs.iBlocked });
      }
      m = path.match(/^\/users\/([\w-]+)\/block$/);
      if (m && method === "POST") {
        if (m[1] === me) return err("cannot block yourself");
        await env.DB.prepare("INSERT OR IGNORE INTO blocks (blocker, blocked, created_at) VALUES (?1,?2,?3)").bind(me, m[1], now()).run();
        return json({ ok: true, blocked: true });
      }
      if (m && method === "DELETE") {
        await env.DB.prepare("DELETE FROM blocks WHERE blocker=?1 AND blocked=?2").bind(me, m[1]).run();
        return json({ ok: true, blocked: false });
      }
      if (path === "/blocks") {
        const { results } = await env.DB.prepare(
          "SELECT u.id, u.username, u.name, u.about, u.avatar FROM blocks b JOIN users u ON u.id=b.blocked WHERE b.blocker=?1 ORDER BY b.created_at DESC"
        ).bind(me).all<any>();
        return json({ users: results.map((r) => userOut(r)) });
      }

      // ---------- groups ----------
      if (path === "/groups" && method === "GET") {
        const { results } = await env.DB.prepare(
          `SELECT g.*,
             (SELECT COUNT(*) FROM group_members gm WHERE gm.group_id=g.id) AS member_count,
             (SELECT body FROM group_messages x WHERE x.group_id=g.id ORDER BY x.created_at DESC LIMIT 1) AS last_body,
             (SELECT type FROM group_messages x WHERE x.group_id=g.id ORDER BY x.created_at DESC LIMIT 1) AS last_type,
             (SELECT created_at FROM group_messages x WHERE x.group_id=g.id ORDER BY x.created_at DESC LIMIT 1) AS last_at
           FROM chat_groups g JOIN group_members mine ON mine.group_id=g.id
           WHERE mine.user_id=?1 ORDER BY COALESCE(last_at,g.updated_at) DESC LIMIT 100`
        ).bind(me).all<any>();
        return json({ groups: results.map((r) => ({ id:r.id,name:r.name,avatar:r.avatar,createdBy:r.created_by,createdAt:r.created_at,memberCount:r.member_count,lastBody:r.last_body,lastType:r.last_type,lastAt:r.last_at })) });
      }
      if (path === "/groups" && method === "POST") {
        const b = await body(req); const name = String(b.name || "").trim().slice(0,60);
        if (!name) return err("Group name is required");
        const id = newId(); const t = now();
        const members: string[] = [...new Set([me, ...(Array.isArray(b.members) ? b.members : [])])].slice(0,51);
        const existing = members.length ? await env.DB.prepare(`SELECT id FROM users WHERE id IN (${members.map((_,i)=>`?${i+1}`).join(",")})`).bind(...members).all<any>() : {results:[]};
        if (existing.results.length < members.length) return err("Some group members were not found");
        const stmts: D1PreparedStatement[] = [env.DB.prepare("INSERT INTO chat_groups (id,name,avatar,created_by,created_at,updated_at) VALUES (?1,?2,?3,?4,?5,?5)").bind(id,name,b.avatar||null,me,t)];
        members.forEach((u) => stmts.push(env.DB.prepare("INSERT INTO group_members (group_id,user_id,role,joined_at) VALUES (?1,?2,?3,?4)").bind(id,u,u===me?"owner":"member",t)));
        await env.DB.batch(stmts);
        const g = { id,name,avatar:b.avatar||null,createdBy:me,createdAt:t,memberCount:members.length };
        await Promise.all(members.filter(u=>u!==me).map(u=>hub(env,u).push({t:"group",k:"created",g})));
        return json({ group:g },201);
      }
      let gm = path.match(/^\/groups\/([\w-]+)$/);
      if (gm && method === "GET") {
        const gid=gm[1];
        const allowed=await env.DB.prepare("SELECT 1 FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,me).first();
        if (!allowed) return err("not found",404);
        const g=await env.DB.prepare("SELECT * FROM chat_groups WHERE id=?1").bind(gid).first<any>();
        const {results:members}=await env.DB.prepare("SELECT u.id,u.username,u.name,u.about,u.avatar,gm.role FROM group_members gm JOIN users u ON u.id=gm.user_id WHERE gm.group_id=?1 ORDER BY CASE gm.role WHEN 'owner' THEN 0 ELSE 1 END,gm.joined_at").bind(gid).all<any>();
        return json({group:{id:g.id,name:g.name,avatar:g.avatar,createdBy:g.created_by,createdAt:g.created_at},members:members.map(x=>({...userOut(x),role:x.role}))});
      }
      gm = path.match(/^\/groups\/([\w-]+)\/messages$/);
      if (gm && method === "GET") {
        const gid=gm[1];
        const allowed=await env.DB.prepare("SELECT 1 FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,me).first();
        if (!allowed) return err("not found",404);
        const before=Number(url.searchParams.get("before")||now()+1);
        const {results}=await env.DB.prepare("SELECT * FROM group_messages WHERE group_id=?1 AND created_at<?2 AND id NOT IN (SELECT message_id FROM group_deleted_for WHERE user_id=?3) ORDER BY created_at DESC LIMIT 300").bind(gid,before,me).all<any>();
        return json({messages:results.reverse().map(r=>({id:r.id,groupId:r.group_id,sender:r.sender,type:r.type,body:r.body,media:r.media,replyTo:r.reply_to,createdAt:r.created_at,updatedAt:r.updated_at}))});
      }
      if (gm && method === "POST") {
        const gid=gm[1]; const b=await body(req); const allowed=await env.DB.prepare("SELECT 1 FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,me).first();
        if(!allowed) return err("not a group member",403);
        const types=new Set(["text","image","video","audio","file"]);
        if(!b.id||!types.has(b.type)|| (b.type==="text"&&!String(b.body||"").trim()) || (b.type!=="text"&&!b.media)) return err("invalid group message");
        const t=now();
        await env.DB.prepare("INSERT OR IGNORE INTO group_messages (id,group_id,sender,type,body,media,reply_to,created_at,updated_at) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?8)").bind(b.id,gid,me,b.type,b.body||null,b.media||null,b.replyTo||null,t).run();
        await env.DB.prepare("UPDATE chat_groups SET updated_at=?1 WHERE id=?2").bind(t,gid).run();
        const {results:members}=await env.DB.prepare("SELECT u.id,u.fcm_token,u.name,u.username FROM group_members gm JOIN users u ON u.id=gm.user_id WHERE gm.group_id=?1 AND gm.user_id<>?2").bind(gid,me).all<any>();
        const g=await env.DB.prepare("SELECT name FROM chat_groups WHERE id=?1").bind(gid).first<any>();
        const out={id:b.id,groupId:gid,sender:me,type:b.type,body:b.body||null,media:b.media||null,replyTo:b.replyTo||null,createdAt:t,updatedAt:t};
        await Promise.all(members.map(async u=>{
          const live=await hub(env,u.id).push({t:"gmsg",m:out,groupName:g?.name||"Group"});
          if(!live&&u.fcm_token) await sendPush(env,u.fcm_token,{kind:"gmsg",id:b.id,groupId:gid,groupName:g?.name||"Group",from:me,name:u.name||u.username||"",type:b.type,text:String(b.body|| (b.type==="image"?"Photo":b.type==="video"?"Video":b.type==="audio"?"Voice message":"File")).slice(0,1000),body:String(b.body||"").slice(0,1500),media:String(b.media||"").slice(0,1000),createdAt:String(t)});
        }));
        return json({message:out});
      }
      gm=path.match(/^\/groups\/([\w-]+)\/members$/);
      if(gm&&method==="POST"){
        const gid=gm[1]; const b=await body(req); const admin=await env.DB.prepare("SELECT role FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,me).first<any>();
        if(!admin||admin.role!=="owner") return err("only group admins can add members",403);
        const ids=[...new Set(Array.isArray(b.members)?b.members:[])].slice(0,50);
        const t=now(); const stmts=ids.map(u=>env.DB.prepare("INSERT OR IGNORE INTO group_members (group_id,user_id,role,joined_at) SELECT ?1,id,'member',?3 FROM users WHERE id=?2").bind(gid,u,t));
        if(stmts.length) await env.DB.batch(stmts);
        await Promise.all(ids.map((u)=>hub(env,String(u)).push({t:"group",k:"added",id:gid})));
        const {results}=await env.DB.prepare("SELECT u.id,u.username,u.name,u.avatar FROM users u JOIN group_members gm ON gm.user_id=u.id WHERE gm.group_id=?1").bind(gid).all<any>();
        return json({members:results.map((r:any)=>userOut(r))});
      }
      gm=path.match(/^\/groups\/([\w-]+)\/members\/([\w-]+)$/);
      if(gm&&method==="DELETE"){
        const gid=gm[1], target=gm[2]; const admin=await env.DB.prepare("SELECT role FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,me).first<any>();
        if(target!==me&&(!admin||admin.role!=="owner")) return err("not allowed",403);
        await env.DB.prepare("DELETE FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,target).run();
        await Promise.all([hub(env,target).push({t:"group",k:"removed",id:gid}),hub(env,me).push({t:"group",k:"changed",id:gid})]);
        return json({ok:true});
      }
      gm=path.match(/^\/groups\/([\w-]+)\/rename$/);
      if(gm&&method==="PUT"){
        const b=await body(req),name=String(b.name||"").trim().slice(0,60); const gid=gm[1];
        const admin=await env.DB.prepare("SELECT role FROM group_members WHERE group_id=?1 AND user_id=?2").bind(gid,me).first<any>();
        if(!admin||admin.role!=="owner") return err("not allowed",403); if(!name) return err("name required");
        await env.DB.prepare("UPDATE chat_groups SET name=?1,updated_at=?2 WHERE id=?3").bind(name,now(),gid).run(); return json({ok:true,name});
      }

      // ---------- 24-hour status ----------
      if (path === "/statuses" && method === "GET") {
        const {results}=await env.DB.prepare(
          `SELECT s.*,u.username,u.name,u.avatar,
             EXISTS(SELECT 1 FROM status_views v WHERE v.status_id=s.id AND v.viewer_id=?1) AS viewed,
             (SELECT COUNT(*) FROM status_views v WHERE v.status_id=s.id) AS views
           FROM statuses s JOIN users u ON u.id=s.user_id WHERE s.expires_at>?2
             AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker=?1 AND b.blocked=s.user_id) OR (b.blocker=s.user_id AND b.blocked=?1))
           ORDER BY s.created_at DESC LIMIT 200`
        ).bind(me,now()).all<any>();
        return json({statuses:results.map(r=>({id:r.id,userId:r.user_id,type:r.type,media:r.media,caption:r.caption,createdAt:r.created_at,expiresAt:r.expires_at,viewed:!!r.viewed,views:r.user_id===me?r.views:undefined,user:userOut(r)}))});
      }
      if(path==="/statuses"&&method==="POST"){
        const b=await body(req); if(!b.media||!new Set(["image","video","text"]).has(b.type)) return err("invalid status");
        const t=now(),id=newId(); await env.DB.prepare("INSERT INTO statuses(id,user_id,type,media,caption,created_at,expires_at) VALUES(?1,?2,?3,?4,?5,?6,?7)").bind(id,me,b.type,typeof b.media==="string"?b.media:JSON.stringify(b.media),String(b.caption||"").slice(0,500),t,t+24*3600_000).run();
        return json({id,expiresAt:t+24*3600_000},201);
      }
      let st=path.match(/^\/statuses\/([\w-]+)\/view$/);
      if(st&&method==="POST"){
        const id=st[1]; const status=await env.DB.prepare("SELECT user_id,expires_at FROM statuses WHERE id=?1").bind(id).first<any>();
        if(!status||status.expires_at<now()) return err("status expired",404);
        if(status.user_id!==me) await env.DB.prepare("INSERT OR IGNORE INTO status_views(status_id,viewer_id,viewed_at) VALUES(?1,?2,?3)").bind(id,me,now()).run();
        return json({ok:true});
      }
      st=path.match(/^\/statuses\/([\w-]+)$/);
      if(st&&method==="DELETE"){
        const r=await env.DB.prepare("DELETE FROM statuses WHERE id=?1 AND user_id=?2").bind(st[1],me).run(); return json({ok:r.meta.changes>0});
      }

      // ---------- sync ----------
      if (path === "/sync") {
        const since = Number(url.searchParams.get("since") || 0);
        const LIMIT = 400;
        const { results: msgs } = await env.DB.prepare(
          `SELECT * FROM (
             SELECT * FROM messages WHERE receiver=?1 AND hidden=0 AND updated_at>?2
             UNION ALL
             SELECT * FROM messages WHERE sender=?1 AND updated_at>?2
           ) WHERE id NOT IN (SELECT message_id FROM deleted_for WHERE user_id=?1)
           ORDER BY updated_at LIMIT ${LIMIT}`
        ).bind(me, since).all<any>();
        const { results: calls } = await env.DB.prepare(
          `SELECT * FROM (
             SELECT * FROM calls WHERE caller=?1 AND updated_at>?2
             UNION ALL SELECT * FROM calls WHERE callee=?1 AND updated_at>?2
           ) ORDER BY updated_at LIMIT 200`
        ).bind(me, since).all<any>();
        const ids = new Set<string>();
        for (const r of msgs) ids.add(r.sender === me ? r.receiver : r.sender);
        for (const r of calls) ids.add(r.caller === me ? r.callee : r.caller);
        let users: any[] = [];
        if (ids.size) {
          const arr = [...ids];
          const ph = arr.map((_, i) => `?${i + 2}`).join(",");
          const { results } = await env.DB.prepare(
            `SELECT u.id, u.username, u.name, u.about, u.avatar,
                    EXISTS(SELECT 1 FROM blocks b WHERE b.blocker=u.id AND b.blocked=?1) AS bm
             FROM users u WHERE u.id IN (${ph})`
          ).bind(me, ...arr).all<any>();
          users = results.map((r) => userOut(r, !!r.bm));
        }
        const { results: bl } = await env.DB.prepare("SELECT blocked FROM blocks WHERE blocker=?1").bind(me).all<any>();
        // hide calls where the callee blocked the caller (callee never saw them)
        return json({
          messages: msgs.map(msgOut), calls: calls.map(callOut), users,
          blocked: bl.map((r) => r.blocked), more: msgs.length >= LIMIT, now: now(),
        });
      }

      // ---------- HTTP fallbacks (notification actions, offline sends) ----------
      if (path === "/messages" && method === "POST") {
        const r = await sendMessage(env, me, await body(req));
        return r.ok ? json({ msg: r.msg }) : err(r.error);
      }
      if (path === "/delivered" && method === "POST") {
        await markDelivered(env, me, (await body(req)).ids);
        return json({ ok: true });
      }
      if (path === "/read" && method === "POST") {
        const b = await body(req);
        await markRead(env, me, b.peer, Number(b.upTo) || now());
        return json({ ok: true });
      }
      if (path === "/messages/delete" && method === "POST") {
        const b = await body(req);
        if (b.forAll) return json({ ok: await deleteForEveryone(env, me, b.id) });
        await deleteForMe(env, me, b.ids || [b.id]);
        return json({ ok: true });
      }
      if (path === "/chats/clear" && method === "POST") {
        await clearChat(env, me, (await body(req)).peer);
        return json({ ok: true });
      }

      // ---------- media / calls config ----------
      if (path === "/call" && method === "POST") {
        await handleCall(env, me, await body(req));
        return json({ ok: true });
      }
      if (path === "/media/sign" && method === "POST") return json(await cloudinarySign(env, me));
      if (path === "/ice") {
        const turn = env.TURN_URL;
        return json({
          iceServers: [
            { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
            { urls: [`turn:${turn}?transport=udp`, `turn:${turn}?transport=tcp`], username: env.TURN_USER, credential: env.TURN_PASS },
          ],
        });
      }
      if (path === "/presence") {
        const u = url.searchParams.get("u") || "";
        const bs = await blockState(env, me, u);
        if (bs.blockedMe || bs.iBlocked) return json({ online: false, lastSeen: 0 });
        const online = await hub(env, u).isOnline();
        const r = await env.DB.prepare("SELECT last_seen FROM users WHERE id=?1").bind(u).first<any>();
        return json({ online, lastSeen: r?.last_seen || 0 });
      }

      return err("not found", 404);
    } catch (e: any) {
      console.error(e?.stack || e);
      return err("server error: " + (e?.message || e), 500);
    }
  },
  async scheduled(_controller: ScheduledController, env: Env, _ctx: ExecutionContext) {
    const cutoff = now();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM status_views WHERE status_id IN (SELECT id FROM statuses WHERE expires_at<=?1)").bind(cutoff),
      env.DB.prepare("DELETE FROM statuses WHERE expires_at<=?1").bind(cutoff),
    ]);
  },
};
