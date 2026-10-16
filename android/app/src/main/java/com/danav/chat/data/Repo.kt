package com.danav.chat.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.danav.chat.call.CallManager
import com.danav.chat.push.Notifier
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Repo {
    private const val TAG = "Repo"
    lateinit var app: Context
    lateinit var db: Db
    lateinit var prefs: Prefs
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val me = MutableStateFlow<User?>(null)
    val needsUsername = MutableStateFlow(false)
    val openChat = MutableStateFlow<String?>(null)
    @Volatile var foreground = false
    @Volatile private var initialized = false
    val uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val syncing = MutableStateFlow(false)
    val themeMode = MutableStateFlow(0)
    private val syncMutex = Mutex()
    private val flushMutex = Mutex()

    val meId: String get() = me.value?.id ?: ""
    val loggedIn get() = prefs.token != null

    @Synchronized fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        themeMode.value = prefs.themeMode
        Api.prefs = prefs
        db = Db(app)
        prefs.meJson?.let { runCatching { me.value = User.from(JSONObject(it)) } }
        needsUsername.value = me.value?.username == null && loggedIn
        scope.launch {
            Realtime.state.collect { if (it == Conn.ONLINE) { sync(); flushPending() } }
        }
        scope.launch { Realtime.events.collect { runCatching { onEvent(it) }.onFailure { e -> Log.e(TAG, "event", e) } } }
    }

    private fun saveMe(j: JSONObject, needs: Boolean) {
        prefs.meJson = j.toString()
        me.value = User.from(j)
        needsUsername.value = needs
    }

    // ---------------- auth & profile ----------------
    suspend fun loginWithGoogle(idToken: String) {
        val r = Api.post("/auth/google", JSONObject().put("idToken", idToken))
        prefs.token = r.getString("token")
        saveMe(r.getJSONObject("user"), r.optBoolean("needsUsername"))
        registerFcm()
    }

    suspend fun refreshMe() {
        runCatching {
            val r = Api.get("/me")
            saveMe(r.getJSONObject("user"), r.optBoolean("needsUsername"))
        }.onFailure { if (it is ApiException && it.code == 401) logout() }
    }

    suspend fun updateProfile(name: String? = null, about: String? = null, username: String? = null, avatar: String? = null, clearAvatar: Boolean = false) {
        val b = JSONObject()
        name?.let { b.put("name", it) }; about?.let { b.put("about", it) }; username?.let { b.put("username", it) }
        if (clearAvatar) b.put("avatar", JSONObject.NULL) else avatar?.let { b.put("avatar", it) }
        val r = Api.put("/me", b)
        saveMe(r.getJSONObject("user"), r.optBoolean("needsUsername"))
    }

    suspend fun checkUsername(u: String): Pair<Boolean, String?> {
        val r = Api.get("/username/check?u=" + Uri.encode(u))
        return r.optBoolean("available") to r.optString("reason").takeIf { it.isNotEmpty() }
    }

    suspend fun uploadAvatar(uri: Uri): String {
        val p = MediaUtils.prepareAvatar(app, uri)
        val r = Api.uploadToCloudinary(p.file, "image", p.mime) {}
        p.file.delete()
        return r.getString("secure_url")
    }

    suspend fun registerFcm() {
        if (!loggedIn) return
        runCatching {
            val t = FirebaseMessaging.getInstance().token.await()
            sendFcmToken(t)
        }.onFailure { Log.w(TAG, "fcm token: ${it.message}") }
    }

    suspend fun sendFcmToken(t: String) {
        if (!loggedIn) return
        runCatching { Api.post("/fcm", JSONObject().put("token", t)); prefs.fcmSent = t }
    }

    fun setTheme(mode: Int) { prefs.themeMode = mode; themeMode.value = mode }

    fun logout() {
        scope.launch { runCatching { Api.post("/logout") } }
        CallManager.hangup()
        AudioPlayer.stop()
        prefs.clear()
        db.wipe()
        me.value = null
        needsUsername.value = false
        Notifier.cancelAll(app)
        Realtime.release("fg")
    }

    // ---------------- sync ----------------
    suspend fun sync() {
        if (!loggedIn) return
        syncMutex.withLock {
            syncing.value = true
            try {
                var since = prefs.lastSync
                var loops = 0
                while (loops++ < 20) {
                    val r = Api.get("/sync?since=$since")
                    val users = r.getJSONArray("users")
                    for (i in 0 until users.length()) db.upsertUser(User.from(users.getJSONObject(i)))
                    val msgs = r.getJSONArray("messages")
                    val list = (0 until msgs.length()).map { Message.fromServer(msgs.getJSONObject(it), meId) }
                    db.upsertServerMessages(list)
                    val calls = r.getJSONArray("calls")
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val outgoing = c.getString("caller") == meId
                        db.upsertCall(CallLog(c.getString("id"), if (outgoing) c.getString("callee") else c.getString("caller"),
                            outgoing, c.optBoolean("video"), c.getString("status"), c.getLong("createdAt"),
                            c.optLongOrNull("answeredAt"), c.optLongOrNull("endedAt")))
                    }
                    val bl = r.getJSONArray("blocked")
                    db.setBlockedList((0 until bl.length()).map { bl.getString(it) })
                    var maxUpd = since
                    for (i in 0 until msgs.length()) maxUpd = maxOf(maxUpd, msgs.getJSONObject(i).getLong("updatedAt"))
                    for (i in 0 until calls.length()) maxUpd = maxOf(maxUpd, calls.getJSONObject(i).getLong("updatedAt"))
                    val more = r.optBoolean("more")
                    since = if (more) maxUpd - 1 else maxUpd
                    prefs.lastSync = since
                    if (!more) break
                }
                runCatching { refreshGroups() }
                runCatching { refreshStatuses() }
                ackDelivered(db.undeliveredIncoming(meId))
                openChat.value?.let { if (foreground) markChatRead(it) }
            } catch (e: Exception) {
                Log.w(TAG, "sync failed: ${e.message}")
                if (e is ApiException && e.code == 401) logout()
            } finally { syncing.value = false }
        }
    }

    private fun ackDelivered(ids: List<String>) {
        if (ids.isEmpty()) return
        val t = System.currentTimeMillis()
        db.setDeliveredLocal(ids, t)
        if (!Realtime.send(JSONObject().put("t", "delivered").put("ids", JSONArray(ids)))) {
            scope.launch { runCatching { Api.post("/delivered", JSONObject().put("ids", JSONArray(ids))) } }
        }
    }

    // ---------------- realtime events ----------------
    private fun onEvent(j: JSONObject) {
        when (j.optString("t")) {
            "ack" -> j.optJSONObject("m")?.let { db.upsertServerMessage(Message.fromServer(it, meId)) }
            "err" -> {
                val id = j.optString("id")
                if (id.isNotEmpty()) db.setStatus(id, MsgStatus.FAILED)
            }
            "msg" -> {
                val m = Message.fromServer(j.getJSONObject("m"), meId)
                j.optJSONObject("u")?.let { u -> if (db.user(u.getString("id")) == null || true) db.upsertUser(User.from(u)) }
                db.upsertServerMessage(m)
                ackDelivered(listOf(m.id))
                if (foreground && openChat.value == m.peer) markChatRead(m.peer)
                else Notifier.showMessages(app, m.peer)
            }
            "status" -> {
                val ids = j.getJSONArray("ids")
                db.markDelivered((0 until ids.length()).map { ids.getString(it) }, j.getLong("d"))
            }
            "read" -> db.markReadByPeer(j.getString("by"), meId, j.getLong("upTo"), j.getLong("at"))
            "deleted" -> { db.markDeleted(j.getString("id")); Notifier.refresh(app) }
            "call" -> CallManager.onSignal(j)
            "gmsg" -> runCatching { db.upsertGroupMessage(groupMessageFrom(j.getJSONObject("m"))) }
            "group" -> scope.launch { runCatching { refreshGroups() } }
        }
    }

    // ---------------- messaging ----------------
    private fun newMsgId() = UUID.randomUUID().toString().replace("-", "")

    fun sendText(peer: String, text: String, replyTo: String?) {
        val t = text.trim()
        if (t.isEmpty()) return
        val m = Message(newMsgId(), peer, meId, peer, "text", t, null, replyTo, System.currentTimeMillis(), null, null, MsgStatus.PENDING)
        db.insertLocal(m)
        scope.launch { flushPending() }
    }

    fun forward(peer: String, original: Message) {
        val forwarded = original.copy(id = newMsgId(), peer = peer, sender = meId, receiver = peer,
            replyTo = null, createdAt = System.currentTimeMillis(), deliveredAt = null, readAt = null,
            status = MsgStatus.PENDING, localPath = null, uploading = false)
        db.insertLocal(forwarded)
        scope.launch { flushPending() }
    }

    /** type: image | video | audio | file */
    fun sendMedia(peer: String, type: String, uri: Uri?, prepared: Prepared?, caption: String?, replyTo: String?, wave: List<Int> = emptyList(), albumId: String? = null) {
        val id = newMsgId()
        scope.launch {
            try {
                val p = prepared ?: when (type) {
                    "image" -> MediaUtils.prepareImage(app, uri!!)
                    "video" -> MediaUtils.prepareVideo(app, uri!!)
                    else -> MediaUtils.prepareFile(app, uri!!)
                }
                val limit = when (type) { "video" -> MediaUtils.MAX_VIDEO_BYTES; "image" -> MediaUtils.MAX_IMAGE_BYTES; "audio" -> MediaUtils.MAX_VIDEO_BYTES; else -> MediaUtils.MAX_FILE_BYTES }
                if (p.file.length() > limit) {
                    Notifier.toast(app, if (type == "video") "Video too large (max 100 MB)" else "File too large (max 10 MB)")
                    return@launch
                }
                val pre = Media(url = "", w = p.w, h = p.h, duration = p.duration, size = p.file.length(), name = p.name, mime = p.mime, wave = wave, albumId = albumId)
                val m = Message(id, peer, meId, peer, type, caption?.trim()?.ifEmpty { null } ?: if (type == "file") p.name else null,
                    pre, replyTo, System.currentTimeMillis(), null, null, MsgStatus.PENDING, localPath = p.file.absolutePath, uploading = true)
                db.insertLocal(m)
                upload(m)
            } catch (e: Exception) {
                Log.e(TAG, "media", e)
                Notifier.toast(app, "Could not attach: ${e.message}")
            }
        }
    }

    private suspend fun upload(m: Message) {
        val file = File(m.localPath ?: return)
        val resType = when (m.type) { "image" -> "image"; "video", "audio" -> "video"; else -> "raw" }
        try {
            uploadProgress.value = uploadProgress.value + (m.id to 0f)
            val r = Api.uploadToCloudinary(file, resType, m.media?.mime ?: "application/octet-stream") { p ->
                uploadProgress.value = uploadProgress.value + (m.id to p)
            }
            val media = (m.media ?: Media()).copy(
                url = r.getString("secure_url"), publicId = r.optString("public_id"),
                w = if (m.media?.w ?: 0 > 0) m.media!!.w else r.optInt("width"),
                h = if (m.media?.h ?: 0 > 0) m.media!!.h else r.optInt("height"),
                size = r.optLong("bytes", file.length()),
            )
            db.updateMessage(m.copy(media = media, uploading = false))
            flushPending()
        } catch (e: Exception) {
            Log.w(TAG, "upload failed ${e.message}")
            db.updateMessage(m.copy(uploading = false, status = MsgStatus.FAILED))
        } finally {
            uploadProgress.value = uploadProgress.value - m.id
        }
    }

    fun retry(m: Message) {
        scope.launch {
            if (m.media != null && m.media.url.isEmpty() && m.localPath != null) {
                val mm = m.copy(status = MsgStatus.PENDING, uploading = true)
                db.updateMessage(mm); upload(mm)
            } else { db.setStatus(m.id, MsgStatus.PENDING); flushPending() }
        }
    }

    suspend fun flushPending() {
        if (!loggedIn) return
        flushMutex.withLock {
            for (m in db.pendingToSend()) {
                val payload = JSONObject().put("id", m.id).put("to", m.peer).put("type", m.type)
                    .put("body", m.body ?: JSONObject.NULL).put("media", m.media?.toJson() ?: JSONObject.NULL)
                    .put("replyTo", m.replyTo ?: JSONObject.NULL)
                if (!Realtime.send(JSONObject().put("t", "msg").put("m", payload))) {
                    try {
                        val r = Api.post("/messages", payload)
                        db.upsertServerMessage(Message.fromServer(r.getJSONObject("msg"), meId))
                    } catch (e: ApiException) {
                        db.setStatus(m.id, MsgStatus.FAILED)
                    } catch (e: Exception) { break } // offline: retry on reconnect
                }
            }
        }
    }

    fun markChatRead(peer: String) {
        val changed = db.markIncomingRead(peer, System.currentTimeMillis())
        Notifier.cancelChat(app, peer)
        if (!changed) return
        val payload = JSONObject().put("peer", peer).put("upTo", System.currentTimeMillis())
        if (!Realtime.send(JSONObject(payload.toString()).put("t", "read")))
            scope.launch { runCatching { Api.post("/read", payload) } }
    }

    fun deleteForMe(ids: Collection<String>) {
        db.deleteMessages(ids)
        val arr = JSONArray(ids.toList())
        if (!Realtime.send(JSONObject().put("t", "del_me").put("ids", arr)))
            scope.launch { runCatching { Api.post("/messages/delete", JSONObject().put("ids", arr)) } }
    }

    fun deleteForEveryone(id: String) {
        db.markDeleted(id)
        if (!Realtime.send(JSONObject().put("t", "del_all").put("id", id)))
            scope.launch { runCatching { Api.post("/messages/delete", JSONObject().put("id", id).put("forAll", true)) } }
    }

    fun clearChat(peer: String) {
        db.clearChat(peer)
        if (!Realtime.send(JSONObject().put("t", "clear").put("peer", peer)))
            scope.launch { runCatching { Api.post("/chats/clear", JSONObject().put("peer", peer)) } }
    }

    private var lastTyping = 0L
    private var lastTypingState = ""
    fun typing(peer: String, state: String) {
        val now = System.currentTimeMillis()
        if (state == lastTypingState && state.isNotEmpty() && now - lastTyping < 3000) return
        if (state.isEmpty() && lastTypingState.isEmpty()) return
        lastTyping = now; lastTypingState = state
        Realtime.send(JSONObject().put("t", "typing").put("to", peer).put("s", state))
    }

    fun subscribePresence(peer: String) {
        Realtime.send(JSONObject().put("t", "sub").put("users", JSONArray(listOf(peer))))
    }

    // ---------------- group conversations and 24-hour statuses ----------------
    private fun groupMessageFrom(j: JSONObject) = GroupMessage(
        id=j.getString("id"), groupId=j.getString("groupId"), sender=j.getString("sender"), type=j.getString("type"),
        body=j.optStr("body"), media=Media.parse(j.optStr("media")), replyTo=j.optStr("replyTo"), createdAt=j.optLong("createdAt")
    )

    suspend fun refreshGroups(): List<ChatGroup> {
        val root=Api.get("/groups").getJSONArray("groups")
        val groups=(0 until root.length()).map { i -> val j=root.getJSONObject(i); ChatGroup(j.getString("id"),j.getString("name"),j.optStr("avatar"),j.optString("createdBy"),j.optLong("createdAt"),j.optInt("memberCount"),j.optStr("lastBody"),j.optStr("lastType"),j.optLongOrNull("lastAt")) }
        db.replaceGroups(groups)
        groups.forEach { g -> runCatching { val arr=Api.get("/groups/${g.id}/messages").getJSONArray("messages"); for(i in 0 until arr.length()) db.upsertGroupMessage(groupMessageFrom(arr.getJSONObject(i))) } }
        return groups
    }

    suspend fun createGroup(name:String, memberIds:List<String>): ChatGroup {
        val r=Api.post("/groups",JSONObject().put("name",name).put("members",JSONArray(memberIds.distinct())))
        val j=r.getJSONObject("group"); val g=ChatGroup(j.getString("id"),j.getString("name"),j.optStr("avatar"),j.optString("createdBy"),j.optLong("createdAt"),j.optInt("memberCount"))
        refreshGroups(); return g
    }

    suspend fun addGroupMembers(groupId:String, memberIds:List<String>) {
        Api.post("/groups/$groupId/members",JSONObject().put("members",JSONArray(memberIds.distinct()))); refreshGroups()
    }

    suspend fun groupMembers(groupId:String):List<User> {
        val a=Api.get("/groups/$groupId").getJSONArray("members")
        return (0 until a.length()).map { User.from(a.getJSONObject(it)) }
    }

    fun sendGroupText(groupId:String,text:String) {
        val t=text.trim(); if(t.isEmpty()) return
        val msg=GroupMessage(newMsgId(),groupId,meId,"text",t,createdAt=System.currentTimeMillis()); db.upsertGroupMessage(msg)
        scope.launch { runCatching { val p=groupPayload(msg); val r=Api.post("/groups/$groupId/messages",p); db.upsertGroupMessage(groupMessageFrom(r.getJSONObject("message"))) }.onFailure { Notifier.toast(app,"Message could not be sent: ${it.message}") } }
    }

    fun sendGroupMedia(groupId:String,type:String,uri:Uri,durationMs:Long=0,wave:List<Int> = emptyList()) {
        scope.launch {
            var prepared: Prepared? = null
            try {
                val p=when(type){"image"->MediaUtils.prepareImage(app,uri);"video"->MediaUtils.prepareVideo(app,uri);else->MediaUtils.prepareFile(app,uri)}
                prepared=p
                if(type=="audio") uri.path?.let { File(it).delete() }
                val max=when(type){"video"->MediaUtils.MAX_VIDEO_BYTES;"audio"->12L*1024*1024;"file"->MediaUtils.MAX_FILE_BYTES;else->MediaUtils.MAX_IMAGE_BYTES}
                if(p.file.length()>max){Notifier.toast(app,"Media file is too large");return@launch}
                val resource=when(type){"video","audio"->"video";"file"->"raw";else->"image"}
                val r=Api.uploadToCloudinary(p.file,resource,p.mime){}
                val media=Media(r.getString("secure_url"),r.optString("public_id"),p.w,p.h,if(type=="audio")durationMs else p.duration,r.optLong("bytes",p.file.length()),p.name,p.mime,wave=wave)
                val msg=GroupMessage(newMsgId(),groupId,meId,type,if(type=="file")p.name else null,media,createdAt=System.currentTimeMillis())
                db.upsertGroupMessage(msg); val response=Api.post("/groups/$groupId/messages",groupPayload(msg)); db.upsertGroupMessage(groupMessageFrom(response.getJSONObject("message")))
            } catch(e:Exception){Notifier.toast(app,"Upload failed: ${e.message}")}
            finally { prepared?.file?.delete() }
        }
    }

    private fun groupPayload(m:GroupMessage)=JSONObject().put("id",m.id).put("type",m.type).put("body",m.body?:JSONObject.NULL).put("media",m.media?.toJson()?:JSONObject.NULL).put("replyTo",m.replyTo?:JSONObject.NULL)

    suspend fun refreshStatuses():List<StoryStatus>{
        val a=Api.get("/statuses").getJSONArray("statuses")
        val list=(0 until a.length()).map{i-> val j=a.getJSONObject(i); StoryStatus(j.getString("id"),User.from(j.getJSONObject("user")),j.getString("type"),Media.parse(j.optStr("media")),j.optString("caption"),j.optLong("createdAt"),j.optLong("expiresAt"),j.optBoolean("viewed"),j.optInt("views")) }
        db.cacheStatuses(list); return list
    }

    suspend fun publishStatus(type:String,media:Media?,caption:String){
        val r=Api.post("/statuses",JSONObject().put("type",type).put("media",media?.toJson()?:JSONObject.NULL).put("caption",caption))
        refreshStatuses()
    }

    suspend fun viewStatus(id:String){Api.post("/statuses/$id/view");db.markStatusViewed(id)}
    suspend fun deleteStatus(id:String){Api.delete("/statuses/$id");db.deleteStatus(id)}

    // ---------------- users ----------------
    suspend fun search(q: String): List<User> {
        val r = Api.get("/users/search?q=" + Uri.encode(q))
        val a = r.getJSONArray("users")
        return (0 until a.length()).map { User.from(a.getJSONObject(it)) }
    }

    suspend fun fetchUser(id: String): User? = runCatching {
        val r = Api.get("/users/$id")
        val u = User.from(r.getJSONObject("user")).copy(blocked = r.optBoolean("iBlocked"))
        db.upsertUser(u, keepBlocked = false)
        u
    }.getOrNull()

    suspend fun setBlocked(id: String, block: Boolean) {
        if (block) Api.post("/users/$id/block") else Api.delete("/users/$id/block")
        db.setBlocked(id, block)
    }

    suspend fun blockedUsers(): List<User> {
        val a = Api.get("/blocks").getJSONArray("users")
        return (0 until a.length()).map { User.from(a.getJSONObject(it)).copy(blocked = true) }
    }

    fun cacheUser(u: User) { if (db.user(u.id) == null) db.upsertUser(u) }
}
