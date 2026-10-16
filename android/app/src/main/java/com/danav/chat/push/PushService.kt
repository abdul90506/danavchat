package com.danav.chat.push

import com.danav.chat.call.CallManager
import com.danav.chat.data.Api
import com.danav.chat.data.GroupMessage
import com.danav.chat.data.Media
import com.danav.chat.data.Message
import com.danav.chat.data.MsgStatus
import com.danav.chat.data.Repo
import com.danav.chat.data.User
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Repo.scope.launch { Repo.sendFcmToken(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        if (!Repo.loggedIn) return
        val d = msg.data
        when (d["kind"]) {
            "msg" -> {
                val from = d["from"] ?: return
                val id = d["id"] ?: return
                val me = Repo.meId
                if (Repo.db.user(from) == null || !d["name"].isNullOrBlank()) {
                    val old = Repo.db.user(from)
                    Repo.db.upsertUser(User(from, old?.username, d["name"] ?: old?.name ?: "", old?.about ?: "", d["avatar"]?.ifBlank { null } ?: old?.avatar))
                }
                val m = Message(
                    id = id, peer = from, sender = from, receiver = me, type = d["type"] ?: "text",
                    body = d["body"]?.ifEmpty { null }, media = Media.parse(d["media"]), replyTo = d["replyTo"]?.ifEmpty { null },
                    createdAt = d["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                    deliveredAt = System.currentTimeMillis(), readAt = null, status = MsgStatus.SENT,
                )
                if (Repo.db.message(id) == null) Repo.db.upsertServerMessage(m)
                // double tick even while the app is closed
                Api.postBlocking("/delivered", JSONObject().put("ids", JSONArray(listOf(id))))
                if (!(Repo.foreground && Repo.openChat.value == from)) Notifier.showMessages(this, from)
            }
            "gmsg" -> {
                val gid=d["groupId"]?:return
                val id=d["id"]?:return
                val media=Media.parse(d["media"])
                Repo.db.upsertGroupMessage(GroupMessage(id,gid,d["from"]?:"",d["type"]?:"text",d["body"]?.ifEmpty{null},media,null,d["createdAt"]?.toLongOrNull()?:System.currentTimeMillis()))
                Repo.scope.launch { runCatching { Repo.refreshGroups() } }
                Notifier.showGroup(this,gid,d["groupName"]?:"Group",d["name"]?:"New message",d["text"]?:"")
            }
            "call" -> {
                val callId = d["callId"] ?: return
                CallManager.onIncomingPush(callId, d["from"] ?: return, d["name"] ?: "", d["avatar"], d["video"] == "1",
                    d["at"]?.toLongOrNull() ?: System.currentTimeMillis())
            }
            "call_end" -> d["callId"]?.let { CallManager.onRemoteEndPush(it) }
        }
    }
}
