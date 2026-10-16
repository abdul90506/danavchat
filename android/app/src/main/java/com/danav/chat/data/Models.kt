package com.danav.chat.data

import org.json.JSONArray
import org.json.JSONObject

data class User(
    val id: String,
    val username: String?,
    val name: String,
    val about: String = "",
    val avatar: String? = null,
    val blocked: Boolean = false,
    val email: String? = null,
) {
    val display: String get() = name.ifBlank { username ?: "Unknown" }

    companion object {
        fun from(j: JSONObject) = User(
            id = j.getString("id"),
            username = j.optStr("username"),
            name = j.optString("name", ""),
            about = j.optString("about", ""),
            avatar = j.optStr("avatar"),
            email = j.optStr("email"),
        )
    }
}

data class Media(
    val url: String = "",
    val publicId: String? = null,
    val w: Int = 0,
    val h: Int = 0,
    val duration: Long = 0, // ms
    val size: Long = 0,
    val name: String? = null,
    val mime: String? = null,
    val wave: List<Int> = emptyList(),
    val albumId: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("url", url); putOpt("publicId", publicId); put("w", w); put("h", h)
        put("duration", duration); put("size", size); putOpt("name", name); putOpt("mime", mime)
        if (wave.isNotEmpty()) put("wave", JSONArray(wave))
        putOpt("albumId", albumId)
    }.toString()

    /** Cloudinary transformations for fast thumbnails */
    fun thumb(width: Int = 640): String {
        if (!url.contains("/upload/")) return url
        return if (url.contains("/video/upload/")) {
            url.replace("/video/upload/", "/video/upload/so_0,w_$width,c_limit,q_auto/").replaceAfterLast('.', "jpg")
        } else url.replace("/image/upload/", "/image/upload/w_$width,c_limit,q_auto,f_auto/")
    }

    companion object {
        fun parse(s: String?): Media? {
            if (s.isNullOrBlank()) return null
            return try {
                val j = JSONObject(s)
                val wa = j.optJSONArray("wave")
                Media(
                    url = j.optString("url"), publicId = j.optStr("publicId"),
                    w = j.optInt("w"), h = j.optInt("h"), duration = j.optLong("duration"),
                    size = j.optLong("size"), name = j.optStr("name"), mime = j.optStr("mime"),
                    wave = if (wa != null) List(wa.length()) { wa.optInt(it) } else emptyList(),
                    albumId = j.optStr("albumId"),
                )
            } catch (e: Exception) { null }
        }
    }
}

object MsgStatus {
    const val FAILED = -1
    const val PENDING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val READ = 3
}

data class Message(
    val id: String,
    val peer: String,
    val sender: String,
    val receiver: String,
    val type: String,
    val body: String?,
    val media: Media?,
    val replyTo: String?,
    val createdAt: Long,
    val deliveredAt: Long?,
    val readAt: Long?,
    val status: Int,
    val localPath: String? = null,
    val uploading: Boolean = false,
) {
    fun isMine(me: String) = sender == me
    val isDeleted get() = type == "deleted"

    companion object {
        fun fromServer(j: JSONObject, me: String): Message {
            val sender = j.getString("sender")
            val receiver = j.getString("receiver")
            val delivered = j.optLongOrNull("deliveredAt")
            val read = j.optLongOrNull("readAt")
            return Message(
                id = j.getString("id"),
                peer = if (sender == me) receiver else sender,
                sender = sender, receiver = receiver,
                type = j.getString("type"),
                body = j.optStr("body"),
                media = Media.parse(j.optStr("media")),
                replyTo = j.optStr("replyTo"),
                createdAt = j.getLong("createdAt"),
                deliveredAt = delivered, readAt = read,
                status = when { read != null -> MsgStatus.READ; delivered != null -> MsgStatus.DELIVERED; else -> MsgStatus.SENT },
            )
        }
    }
}

data class ChatGroup(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val memberCount: Int = 0,
    val lastBody: String? = null,
    val lastType: String? = null,
    val lastAt: Long? = null,
)

data class GroupMessage(
    val id: String,
    val groupId: String,
    val sender: String,
    val type: String,
    val body: String? = null,
    val media: Media? = null,
    val replyTo: String? = null,
    val createdAt: Long,
)

data class StoryStatus(
    val id: String,
    val user: User,
    val type: String,
    val media: Media?,
    val caption: String,
    val createdAt: Long,
    val expiresAt: Long,
    val viewed: Boolean,
    val views: Int = 0,
)

data class CallLog(
    val id: String,
    val peer: String,
    val outgoing: Boolean,
    val video: Boolean,
    val status: String,
    val createdAt: Long,
    val answeredAt: Long?,
    val endedAt: Long?,
) {
    val missed get() = !outgoing && status in setOf("missed", "rejected", "busy", "ringing")
}

data class ChatSummary(val peer: User, val last: Message, val unread: Int)

fun JSONObject.optStr(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)
