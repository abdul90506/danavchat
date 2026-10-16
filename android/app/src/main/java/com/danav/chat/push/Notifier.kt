package com.danav.chat.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.danav.chat.MainActivity
import com.danav.chat.R
import com.danav.chat.data.Api
import com.danav.chat.data.Message
import com.danav.chat.data.Repo
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID

object Notifier {
    const val CH_MESSAGES = "messages_v1"
    const val CH_CALLS = "calls_v1"
    const val CH_ONGOING = "ongoing_v1"
    const val CALL_NOTIF_ID = 4242
    const val KEY_REPLY = "reply_text"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New chat messages"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build())
        })
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing for incoming voice and video calls"
            setSound(null, null) // ringtone played by CallService
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        })
    }

    private fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun loadBitmap(ctx: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            runBlocking {
                val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(192).allowHardware(false).build())
                (r as? SuccessResult)?.drawable?.toBitmap(192, 192)?.let { circle(it) }
            }
        }.getOrNull()
    }

    private fun circle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(src.width / 2f, src.height / 2f, src.width / 2f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, src.width, src.height), p)
        return out
    }

    private fun preview(m: Message): String = when (m.type) {
        "image" -> "📷 " + (m.body ?: "Photo")
        "video" -> "🎥 " + (m.body ?: "Video")
        "audio" -> "🎤 Voice message"
        "file" -> "📄 " + (m.body ?: "Document")
        "deleted" -> "🚫 This message was deleted"
        else -> m.body ?: ""
    }

    private fun notifId(peer: String) = peer.hashCode()

    /** Show / update the conversation notification for a chat from the local DB. Call off the main thread. */
    fun showMessages(ctx: Context, peer: String) {
        if (!canNotify(ctx)) return
        Repo.scope.launch {
            val msgs = Repo.db.unreadIncoming(peer)
            if (msgs.isEmpty()) { cancelChat(ctx, peer); return@launch }
            val user = Repo.db.user(peer)
            val name = user?.display ?: "New message"
            val bmp = loadBitmap(ctx, user?.avatar)
            val sender = Person.Builder().setName(name).setKey(peer).apply { bmp?.let { setIcon(IconCompat.createWithBitmap(it)) } }.build()
            val meP = Person.Builder().setName("You").build()
            val style = NotificationCompat.MessagingStyle(meP)
            msgs.forEach { style.addMessage(preview(it), it.createdAt, sender) }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(ctx, notifId(peer),
                Intent(ctx, MainActivity::class.java).putExtra("openChat", peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
            val replyPi = PendingIntent.getBroadcast(ctx, notifId(peer) + 1,
                Intent(ctx, ReplyReceiver::class.java).setAction(ReplyReceiver.REPLY).putExtra("peer", peer),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val readPi = PendingIntent.getBroadcast(ctx, notifId(peer) + 2,
                Intent(ctx, ReplyReceiver::class.java).setAction(ReplyReceiver.READ).putExtra("peer", peer), flags)
            val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
            val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_stat_danav, "Reply", replyPi)
                .addRemoteInput(remote).setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
            val readAction = NotificationCompat.Action.Builder(R.drawable.ic_stat_danav, "Mark as read", readPi)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()

            val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_danav)
                .setColor(0xFF1DAB61.toInt())
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup("chats")
                .setNumber(msgs.size)
                .addAction(replyAction).addAction(readAction)
                .apply { bmp?.let { setLargeIcon(it) } }
                .build()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
            try { NotificationManagerCompat.from(ctx).notify(notifId(peer), n) } catch (_: SecurityException) { }
        }
    }

    fun showGroup(ctx: Context, groupId: String, groupName: String, sender: String, text: String) {
        if (!canNotify(ctx)) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(ctx, groupId.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("openGroup", groupId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_danav).setColor(0xFF1DAB61.toInt())
            .setContentTitle(groupName).setContentText("$sender: $text").setStyle(NotificationCompat.BigTextStyle().bigText("$sender: $text"))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(groupId.hashCode(), n) } catch (_: SecurityException) { }
    }

    fun refresh(ctx: Context) {}

    fun cancelChat(ctx: Context, peer: String) = NotificationManagerCompat.from(ctx).cancel(notifId(peer))
    fun cancelAll(ctx: Context) = NotificationManagerCompat.from(ctx).cancelAll()

    fun toast(ctx: Context, msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra("peer") ?: return
        val pending = goAsync()
        Repo.scope.launch {
            try {
                when (intent.action) {
                    REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            Repo.markChatRead(peer)
                            Repo.sendText(peer, text, null)
                            Repo.flushPending()
                        }
                        Notifier.cancelChat(context, peer)
                    }
                    READ -> Repo.markChatRead(peer)
                }
            } finally { pending.finish() }
        }
    }
    companion object {
        const val REPLY = "com.danav.chat.REPLY"
        const val READ = "com.danav.chat.READ"
    }
}
