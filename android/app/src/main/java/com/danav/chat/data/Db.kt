package com.danav.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Small hand-written SQLite store with change notifications (no annotation processing needed). */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "danav.db", null, 2) {

    private val version = MutableStateFlow(0L)
    fun bump() { version.value = version.value + 1 }

    fun <T> observe(block: () -> T): Flow<T> =
        version.map { block() }.flowOn(Dispatchers.IO).distinctUntilChanged()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE users(id TEXT PRIMARY KEY, username TEXT, name TEXT, about TEXT, avatar TEXT, blocked INTEGER DEFAULT 0)""")
        db.execSQL("""CREATE TABLE messages(id TEXT PRIMARY KEY, peer TEXT, sender TEXT, receiver TEXT, type TEXT, body TEXT,
            media TEXT, reply_to TEXT, created_at INTEGER, delivered_at INTEGER, read_at INTEGER, status INTEGER,
            local_path TEXT, uploading INTEGER DEFAULT 0)""")
        db.execSQL("CREATE INDEX idx_m_peer ON messages(peer, created_at)")
        db.execSQL("""CREATE TABLE calls(id TEXT PRIMARY KEY, peer TEXT, outgoing INTEGER, video INTEGER, status TEXT,
            created_at INTEGER, answered_at INTEGER, ended_at INTEGER)""")
        createSocialTables(db)
    }

    private fun createSocialTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS groups_local(id TEXT PRIMARY KEY,name TEXT,avatar TEXT,created_by TEXT,created_at INTEGER,member_count INTEGER,last_body TEXT,last_type TEXT,last_at INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS group_messages_local(id TEXT PRIMARY KEY,group_id TEXT,sender TEXT,type TEXT,body TEXT,media TEXT,reply_to TEXT,created_at INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_local ON group_messages_local(group_id,created_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS statuses_local(id TEXT PRIMARY KEY,user_id TEXT,username TEXT,name TEXT,avatar TEXT,type TEXT,media TEXT,caption TEXT,created_at INTEGER,expires_at INTEGER,viewed INTEGER,views INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSocialTables(db)
    }

    // ---------------- users ----------------
    fun upsertUser(u: User, keepBlocked: Boolean = true) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", u.id); put("username", u.username); put("name", u.name); put("about", u.about); put("avatar", u.avatar)
        }
        val updated = db.update("users", cv, "id=?", arrayOf(u.id))
        if (updated == 0) { cv.put("blocked", if (u.blocked) 1 else 0); db.insert("users", null, cv) }
        else if (!keepBlocked) db.execSQL("UPDATE users SET blocked=? WHERE id=?", arrayOf(if (u.blocked) 1 else 0, u.id))
        bump()
    }

    fun setBlockedList(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE users SET blocked=0")
            ids.forEach { db.execSQL("UPDATE users SET blocked=1 WHERE id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }

    fun setBlocked(id: String, blocked: Boolean) {
        writableDatabase.execSQL("UPDATE users SET blocked=? WHERE id=?", arrayOf(if (blocked) 1 else 0, id)); bump()
    }

    fun user(id: String): User? = readableDatabase.rawQuery("SELECT * FROM users WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.toUser() else null
    }

    fun users(): List<User> = readableDatabase.rawQuery("SELECT * FROM users WHERE blocked=0 ORDER BY name COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) add(c.toUser()) }
    }

    fun observeUser(id: String) = observe { user(id) }

    // ---------------- messages ----------------
    private fun Message.cv() = ContentValues().apply {
        put("id", id); put("peer", peer); put("sender", sender); put("receiver", receiver); put("type", type)
        put("body", body); put("media", media?.toJson()); put("reply_to", replyTo); put("created_at", createdAt)
        put("delivered_at", deliveredAt); put("read_at", readAt); put("status", status); put("local_path", localPath)
        put("uploading", if (uploading) 1 else 0)
    }

    /** Insert or merge a message coming from the server (keeps local file path, never downgrades status). */
    fun upsertServerMessage(m: Message) {
        val db = writableDatabase
        val existing = message(m.id)
        if (existing == null) {
            db.insertWithOnConflict("messages", null, m.cv(), SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            val merged = m.copy(
                localPath = existing.localPath,
                status = maxOf(existing.status, m.status),
                readAt = m.readAt ?: existing.readAt,
                deliveredAt = m.deliveredAt ?: existing.deliveredAt,
            )
            db.update("messages", merged.cv(), "id=?", arrayOf(m.id))
        }
        bump()
    }

    fun upsertServerMessages(list: List<Message>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try { list.forEach { upsertServerMessageNoBump(it) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        bump()
    }

    private fun upsertServerMessageNoBump(m: Message) {
        val db = writableDatabase
        val existing = message(m.id)
        if (existing == null) db.insertWithOnConflict("messages", null, m.cv(), SQLiteDatabase.CONFLICT_REPLACE)
        else db.update("messages", m.copy(
            localPath = existing.localPath, status = maxOf(existing.status, m.status),
            readAt = m.readAt ?: existing.readAt, deliveredAt = m.deliveredAt ?: existing.deliveredAt,
        ).cv(), "id=?", arrayOf(m.id))
    }

    fun insertLocal(m: Message) { writableDatabase.insertWithOnConflict("messages", null, m.cv(), SQLiteDatabase.CONFLICT_REPLACE); bump() }

    fun updateMessage(m: Message) { writableDatabase.update("messages", m.cv(), "id=?", arrayOf(m.id)); bump() }

    fun message(id: String): Message? =
        readableDatabase.rawQuery("SELECT * FROM messages WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.toMessage() else null }

    fun setStatus(id: String, status: Int) {
        writableDatabase.execSQL("UPDATE messages SET status=? WHERE id=?", arrayOf(status, id)); bump()
    }

    fun markDelivered(ids: List<String>, at: Long) {
        val db = writableDatabase
        ids.forEach { db.execSQL("UPDATE messages SET delivered_at=?, status=MAX(status, 2) WHERE id=? AND status>=1", arrayOf(at, it)) }
        bump()
    }

    /** peer has read my messages up to [upTo] */
    fun markReadByPeer(peer: String, me: String, upTo: Long, at: Long) {
        writableDatabase.execSQL(
            "UPDATE messages SET read_at=?, delivered_at=COALESCE(delivered_at, ?), status=3 WHERE peer=? AND sender=? AND created_at<=? AND status>=1",
            arrayOf(at, at, peer, me, upTo))
        bump()
    }

    /** I read the incoming messages of this chat */
    fun markIncomingRead(peer: String, at: Long): Boolean {
        val db = writableDatabase
        db.execSQL("UPDATE messages SET read_at=? WHERE peer=? AND sender=? AND read_at IS NULL", arrayOf(at, peer, peer))
        val changed = db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (changed) bump()
        return changed
    }

    fun undeliveredIncoming(me: String): List<String> =
        readableDatabase.rawQuery("SELECT id FROM messages WHERE receiver=? AND delivered_at IS NULL LIMIT 200", arrayOf(me)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun setDeliveredLocal(ids: List<String>, at: Long) {
        val db = writableDatabase
        ids.forEach { db.execSQL("UPDATE messages SET delivered_at=? WHERE id=? AND delivered_at IS NULL", arrayOf(at, it)) }
    }

    fun markDeleted(id: String) {
        writableDatabase.execSQL("UPDATE messages SET type='deleted', body=NULL, media=NULL, reply_to=NULL WHERE id=?", arrayOf(id)); bump()
    }

    fun deleteMessages(ids: Collection<String>) {
        val db = writableDatabase
        ids.forEach { db.delete("messages", "id=?", arrayOf(it)) }
        bump()
    }

    fun clearChat(peer: String) { writableDatabase.delete("messages", "peer=?", arrayOf(peer)); bump() }

    fun messages(peer: String, limit: Int): List<Message> =
        readableDatabase.rawQuery(
            "SELECT * FROM (SELECT * FROM messages WHERE peer=? ORDER BY created_at DESC LIMIT ?) ORDER BY created_at ASC",
            arrayOf(peer, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun observeMessages(peer: String, limit: Int) = observe { messages(peer, limit) }

    fun pendingToSend(): List<Message> =
        readableDatabase.rawQuery("SELECT * FROM messages WHERE status=0 AND uploading=0 ORDER BY created_at ASC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun unreadIncoming(peer: String, limit: Int = 8): List<Message> =
        readableDatabase.rawQuery(
            "SELECT * FROM (SELECT * FROM messages WHERE peer=? AND sender=? AND read_at IS NULL ORDER BY created_at DESC LIMIT ?) ORDER BY created_at",
            arrayOf(peer, peer, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun chats(): List<ChatSummary> {
        val db = readableDatabase
        val unread = HashMap<String, Int>()
        db.rawQuery("SELECT peer, COUNT(*) FROM messages WHERE sender=peer AND read_at IS NULL AND type<>'deleted' GROUP BY peer", null).use { c ->
            while (c.moveToNext()) unread[c.getString(0)] = c.getInt(1)
        }
        val out = ArrayList<ChatSummary>()
        db.rawQuery(
            """SELECT m.* FROM messages m JOIN (SELECT peer, MAX(created_at) mc FROM messages GROUP BY peer) x
               ON m.peer=x.peer AND m.created_at=x.mc GROUP BY m.peer ORDER BY m.created_at DESC""", null).use { c ->
            while (c.moveToNext()) {
                val m = c.toMessage()
                val u = user(m.peer) ?: User(m.peer, null, "Danav user")
                out.add(ChatSummary(u, m, unread[m.peer] ?: 0))
            }
        }
        return out
    }

    fun observeChats() = observe { chats() }

    // ---------------- groups & stories ----------------
    fun replaceGroups(list: List<ChatGroup>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            list.forEach { g ->
                val cv = ContentValues().apply { put("id",g.id);put("name",g.name);put("avatar",g.avatar);put("created_by",g.createdBy);put("created_at",g.createdAt);put("member_count",g.memberCount);put("last_body",g.lastBody);put("last_type",g.lastType);put("last_at",g.lastAt) }
                db.insertWithOnConflict("groups_local",null,cv,SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump()
    }

    fun groups(): List<ChatGroup> = readableDatabase.rawQuery("SELECT * FROM groups_local ORDER BY COALESCE(last_at,created_at) DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(ChatGroup(c.s("id")!!,c.s("name")?:"Group",c.s("avatar"),c.s("created_by")?:"",c.l("created_at")?:0,c.i("member_count"),c.s("last_body"),c.s("last_type"),c.l("last_at"))) }
    }
    fun observeGroups() = observe { groups() }
    fun upsertGroupMessage(m: GroupMessage) {
        writableDatabase.insertWithOnConflict("group_messages_local",null,ContentValues().apply { put("id",m.id);put("group_id",m.groupId);put("sender",m.sender);put("type",m.type);put("body",m.body);put("media",m.media?.toJson());put("reply_to",m.replyTo);put("created_at",m.createdAt) },SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("UPDATE groups_local SET last_body=?,last_type=?,last_at=? WHERE id=?",arrayOf(m.body,m.type,m.createdAt,m.groupId)); bump()
    }
    fun groupMessages(groupId: String): List<GroupMessage> = readableDatabase.rawQuery("SELECT * FROM group_messages_local WHERE group_id=? ORDER BY created_at ASC LIMIT 500",arrayOf(groupId)).use { c ->
        buildList { while(c.moveToNext()) add(GroupMessage(c.s("id")!!,c.s("group_id")!!,c.s("sender")!!,c.s("type")?:"text",c.s("body"),Media.parse(c.s("media")),c.s("reply_to"),c.l("created_at")?:0)) }
    }
    fun observeGroupMessages(groupId: String) = observe { groupMessages(groupId) }
    fun cacheStatuses(list: List<StoryStatus>) {
        val db=writableDatabase; db.beginTransaction()
        try { list.forEach { s -> val u=s.user; db.insertWithOnConflict("statuses_local",null,ContentValues().apply { put("id",s.id);put("user_id",u.id);put("username",u.username);put("name",u.name);put("avatar",u.avatar);put("type",s.type);put("media",s.media?.toJson());put("caption",s.caption);put("created_at",s.createdAt);put("expires_at",s.expiresAt);put("viewed",if(s.viewed)1 else 0);put("views",s.views) },SQLiteDatabase.CONFLICT_REPLACE) }; db.delete("statuses_local","expires_at<=?",arrayOf(System.currentTimeMillis().toString())); db.setTransactionSuccessful() } finally { db.endTransaction() }; bump()
    }
    fun statuses(): List<StoryStatus> = readableDatabase.rawQuery("SELECT * FROM statuses_local WHERE expires_at>? ORDER BY created_at DESC",arrayOf(System.currentTimeMillis().toString())).use { c ->
        buildList { while(c.moveToNext()) { val u=User(c.s("user_id")!!,c.s("username"),c.s("name")?:"",avatar=c.s("avatar")); add(StoryStatus(c.s("id")!!,u,c.s("type")?:"image",Media.parse(c.s("media")),c.s("caption")?:"",c.l("created_at")?:0,c.l("expires_at")?:0,c.i("viewed")==1,c.i("views"))) } }
    }
    fun observeStatuses() = observe { statuses() }
    fun markStatusViewed(id: String) { writableDatabase.execSQL("UPDATE statuses_local SET viewed=1 WHERE id=?",arrayOf(id)); bump() }
    fun deleteStatus(id: String) { writableDatabase.delete("statuses_local","id=?",arrayOf(id)); bump() }

    // ---------------- calls ----------------
    fun upsertCall(c: CallLog) {
        writableDatabase.insertWithOnConflict("calls", null, ContentValues().apply {
            put("id", c.id); put("peer", c.peer); put("outgoing", if (c.outgoing) 1 else 0); put("video", if (c.video) 1 else 0)
            put("status", c.status); put("created_at", c.createdAt); put("answered_at", c.answeredAt); put("ended_at", c.endedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun calls(): List<Pair<CallLog, User>> =
        readableDatabase.rawQuery("SELECT * FROM calls ORDER BY created_at DESC LIMIT 300", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val cl = CallLog(c.s("id")!!, c.s("peer")!!, c.i("outgoing") == 1, c.i("video") == 1, c.s("status") ?: "",
                        c.l("created_at") ?: 0, c.l("answered_at"), c.l("ended_at"))
                    add(cl to (user(cl.peer) ?: User(cl.peer, null, "Danav user")))
                }
            }
        }

    fun observeCalls() = observe { calls() }

    fun wipe() {
        val db = writableDatabase
        db.delete("messages", null, null); db.delete("users", null, null); db.delete("calls", null, null)
        bump()
    }

    // ---------------- cursor helpers ----------------
    private fun Cursor.s(n: String): String? = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.l(n: String): Long? = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.i(n: String): Int = getColumnIndex(n).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

    private fun Cursor.toUser() = User(s("id")!!, s("username"), s("name") ?: "", s("about") ?: "", s("avatar"), i("blocked") == 1)

    private fun Cursor.toMessage() = Message(
        id = s("id")!!, peer = s("peer")!!, sender = s("sender")!!, receiver = s("receiver")!!, type = s("type") ?: "text",
        body = s("body"), media = Media.parse(s("media")), replyTo = s("reply_to"), createdAt = l("created_at") ?: 0,
        deliveredAt = l("delivered_at"), readAt = l("read_at"), status = i("status"), localPath = s("local_path"),
        uploading = i("uploading") == 1,
    )
}
