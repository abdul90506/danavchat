package com.danav.chat.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.danav.chat.R
import com.danav.chat.push.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps a call alive, rings for incoming calls and shows the call notification. */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ringtone: Ringtone? = null
    private var ringing = false
    private var vibrator: Vibrator? = null
    private var proximity: PowerManager.WakeLock? = null
    private var avatar: Bitmap? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        job = scope.launch {
            CallManager.state.map { it?.let { s -> Triple(s.phase, s.callId, s.video) } }.distinctUntilChanged().collect { key ->
                val s = CallManager.state.value
                if (key == null || s == null || s.phase == Phase.ENDED) {
                    stopRinging(); releaseProximity()
                    ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                if (s.phase == Phase.INCOMING) startRinging() else stopRinging()
                if (!s.video && s.phase in setOf(Phase.CONNECTED, Phase.CONNECTING, Phase.CALLING, Phase.RINGING)) acquireProximity() else releaseProximity()
                promote(s)
            }
        }
        scope.launch(Dispatchers.IO) {
            val s = CallManager.state.value ?: return@launch
            avatar = Notifier.loadBitmap(this@CallService, s.peerAvatar)
            CallManager.state.value?.let { if (it.phase != Phase.ENDED) launch(Dispatchers.Main) { promote(it) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = CallManager.state.value
        if (s == null || s.phase == Phase.ENDED) {
            // Must still call startForeground before stopping
            promote(CallUi("x", "", "Danav Chat", null, false, true, Phase.ENDED))
            stopSelf()
        } else promote(s)
        return START_NOT_STICKY
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun promote(s: CallUi) {
        val n = build(s)
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (s.phase != Phase.INCOMING && s.phase != Phase.ENDED) {
                if (hasPerm(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (s.video && hasPerm(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        try {
            ServiceCompat.startForeground(this, Notifier.CALL_NOTIF_ID, n, type)
        } catch (e: Exception) {
            runCatching { ServiceCompat.startForeground(this, Notifier.CALL_NOTIF_ID, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0) }
        }
    }

    private fun build(s: CallUi): Notification {
        val person = Person.Builder().setName(s.peerName).setImportant(true)
            .apply { avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }.build()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 10, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val incoming = s.phase == Phase.INCOMING
        val b = NotificationCompat.Builder(this, if (incoming) Notifier.CH_CALLS else Notifier.CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_danav)
            .setContentTitle(s.peerName)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setColor(0xFF1DAB61.toInt())
            .setOnlyAlertOnce(!incoming)
        avatar?.let { b.setLargeIcon(it) }
        if (incoming) {
            val answer = PendingIntent.getActivity(this, 11, Intent(this, CallActivity::class.java)
                .setAction(CallActivity.ACTION_ACCEPT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            val decline = PendingIntent.getBroadcast(this, 12, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE), flags)
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(s.video))
                .setContentText(if (s.video) "Incoming video call" else "Incoming voice call")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(open, true)
        } else {
            val hang = PendingIntent.getBroadcast(this, 13, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.HANGUP), flags)
            b.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hang).setIsVideo(s.video))
                .setContentText(when (s.phase) {
                    Phase.CALLING -> "Calling…"; Phase.RINGING -> "Ringing…"; Phase.CONNECTING -> "Connecting…"
                    Phase.RECONNECTING -> "Reconnecting…"; else -> if (s.video) "Ongoing video call" else "Ongoing voice call"
                })
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (s.connectedAt > 0) b.setUsesChronometer(true).setWhen(s.connectedAt).setShowWhen(true)
        }
        return b.build()
    }

    private fun startRinging() {
        if (ringing) return
        ringing = true
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }
        }
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                       else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1000), 0)) }
        }
    }

    private fun stopRinging() {
        ringing = false
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { vibrator?.cancel() }; vibrator = null
    }

    @Suppress("WakelockTimeout")
    private fun acquireProximity() {
        if (proximity != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "danav:proximity").apply { acquire() }
        }
    }

    private fun releaseProximity() { runCatching { if (proximity?.isHeld == true) proximity?.release() }; proximity = null }

    override fun onDestroy() {
        stopRinging(); releaseProximity()
        job?.cancel(); scope.cancel()
        super.onDestroy()
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE -> CallManager.reject()
            HANGUP -> CallManager.hangup()
        }
    }
    companion object {
        const val DECLINE = "com.danav.chat.DECLINE"
        const val HANGUP = "com.danav.chat.HANGUP"
    }
}
