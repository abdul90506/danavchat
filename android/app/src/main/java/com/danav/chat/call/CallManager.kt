package com.danav.chat.call

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.danav.chat.data.Api
import com.danav.chat.data.Realtime
import com.danav.chat.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Phase { CALLING, RINGING, INCOMING, CONNECTING, CONNECTED, RECONNECTING, ENDED }

data class CallUi(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val peerAvatar: String?,
    val video: Boolean,
    val outgoing: Boolean,
    val phase: Phase,
    val connectedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val camOn: Boolean = true,
    val remoteCamOn: Boolean = true,
    val frontCam: Boolean = true,
    val endReason: String? = null,
)

/** WebRTC 1:1 voice & video calls, signalled through the user's Durable Object hub. */
object CallManager {
    private const val TAG = "CallManager"
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<CallUi?>(null)
    val state = _state.asStateFlow()
    val localVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)

    lateinit var app: Context
    val eglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteDescSet = false
    private val seenCalls = LinkedHashSet<String>()
    private var timeoutJob: Job? = null
    private var tone: ToneGenerator? = null
    private var iceServers: List<PeerConnection.IceServer>? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    val active get() = _state.value?.let { it.phase != Phase.ENDED } == true

    private fun update(f: (CallUi) -> CallUi) { _state.value?.let { _state.value = f(it) } }

    private fun signal(k: String, extra: JSONObject.() -> Unit = {}) {
        val s = _state.value ?: return
        val j = JSONObject().put("t", "call").put("k", k).put("to", s.peerId).put("callId", s.callId)
        j.extra()
        if (!Realtime.send(j)) {
            // fallback over HTTP for important frames
            if (k in setOf("invite", "accept", "reject", "end", "busy")) scope.launch {
                Realtime.awaitOnline(6000)
                if (!Realtime.send(j)) Api.postBlocking("/call", j)
            }
        }
    }

    // ---------------------------------------------------------------- outgoing
    fun startOutgoing(peerId: String, name: String, avatar: String?, video: Boolean) = scope.launch {
        if (active) return@launch
        val id = UUID.randomUUID().toString()
        seenCalls.add(id)
        _state.value = CallUi(id, peerId, name, avatar, video, true, Phase.CALLING, speaker = video)
        launchUi(null)
        Realtime.hold("call")
        startService()
        try {
            setupMedia(video)
            setupAudioMode(video)
            if (!Realtime.awaitOnline(10_000)) { endLocal("No internet connection"); return@launch }
            signal("invite") { put("video", video) }
            timeoutJob = scope.launch { delay(45_000); if (_state.value?.phase in setOf(Phase.CALLING, Phase.RINGING)) hangupInternal("No answer") }
        } catch (e: Exception) {
            Log.e(TAG, "start", e); endLocal("Call failed")
        }
    }

    // ---------------------------------------------------------------- incoming
    /** Incoming call via FCM data push (app may have been killed). */
    fun onIncomingPush(callId: String, from: String, name: String, avatar: String?, video: Boolean, at: Long) = scope.launch {
        if (System.currentTimeMillis() - at > 45_000) return@launch
        incoming(callId, from, name, avatar, video)
    }

    private fun incoming(callId: String, from: String, name: String, avatar: String?, video: Boolean) {
        if (callId in seenCalls) return
        seenCalls.add(callId); if (seenCalls.size > 50) seenCalls.remove(seenCalls.first())
        if (active) {
            val j = JSONObject().put("t", "call").put("k", "busy").put("to", from).put("callId", callId)
            if (!Realtime.send(j)) scope.launch { Api.postBlocking("/call", j) }
            return
        }
        _state.value = CallUi(callId, from, name.ifBlank { "Danav user" }, avatar?.ifBlank { null }, video, false, Phase.INCOMING, speaker = video)
        Realtime.hold("call")
        startService()
        if (Repo.foreground) launchUi(null)
        scope.launch { if (Realtime.awaitOnline(10_000)) signal("ringing") }
        timeoutJob = scope.launch { delay(45_000); if (_state.value?.phase == Phase.INCOMING) endLocal("Missed call") }
    }

    fun accept() = scope.launch {
        val s = _state.value ?: return@launch
        if (s.phase != Phase.INCOMING) return@launch
        timeoutJob?.cancel()
        update { it.copy(phase = Phase.CONNECTING) }
        startService() // refresh FGS type with microphone/camera
        try {
            setupMedia(s.video)
            setupAudioMode(s.video)
            createPeer()
            if (!Realtime.awaitOnline(10_000)) { endLocal("No internet connection"); return@launch }
            signal("accept")
            timeoutJob = scope.launch { delay(30_000); if (_state.value?.phase == Phase.CONNECTING) hangupInternal("Connection failed") }
        } catch (e: Exception) { Log.e(TAG, "accept", e); hangupInternal("Call failed") }
    }

    fun reject() = scope.launch {
        if (_state.value?.phase != Phase.INCOMING) return@launch
        signal("reject")
        endLocal("Declined")
    }

    fun hangup() = scope.launch { hangupInternal("Call ended") }

    private fun hangupInternal(reason: String) {
        val s = _state.value ?: return
        if (s.phase == Phase.ENDED) return
        if (s.phase == Phase.INCOMING) signal("reject") else signal("end")
        endLocal(reason)
    }

    // ---------------------------------------------------------------- signalling in
    fun onSignal(j: JSONObject) = scope.launch {
        val k = j.optString("k")
        val callId = j.optString("callId")
        val from = j.optString("from")
        if (k == "invite") {
            incoming(callId, from, j.optString("name"), j.optString("avatar"), j.optBoolean("video")); return@launch
        }
        val s = _state.value ?: return@launch
        if (s.callId != callId) return@launch
        when (k) {
            "ringing" -> if (s.phase == Phase.CALLING) { update { it.copy(phase = Phase.RINGING) }; startRingback() }
            "accept" -> if (s.outgoing && s.phase in setOf(Phase.CALLING, Phase.RINGING)) {
                stopRingback(); timeoutJob?.cancel()
                update { it.copy(phase = Phase.CONNECTING) }
                createPeer()
                val offer = pc!!.createOfferAwait()
                pc!!.setLocalAwait(offer)
                signal("sdp") { put("sdp", offer.description); put("sdpType", "offer") }
                timeoutJob = scope.launch { delay(30_000); if (_state.value?.phase == Phase.CONNECTING) hangupInternal("Connection failed") }
            }
            "sdp" -> {
                val type = j.optString("sdpType")
                val desc = SessionDescription(if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, j.getString("sdp"))
                val p = pc ?: return@launch
                p.setRemoteAwait(desc)
                remoteDescSet = true
                pendingIce.forEach { p.addIceCandidate(it) }; pendingIce.clear()
                if (type == "offer") {
                    val ans = p.createAnswerAwait()
                    p.setLocalAwait(ans)
                    signal("sdp") { put("sdp", ans.description); put("sdpType", "answer") }
                }
            }
            "ice" -> {
                val c = j.optJSONObject("c") ?: return@launch
                val cand = IceCandidate(c.optString("mid"), c.optInt("idx"), c.optString("sdp"))
                if (remoteDescSet && pc != null) pc!!.addIceCandidate(cand) else pendingIce.add(cand)
            }
            "media" -> update { it.copy(remoteCamOn = j.optBoolean("on", true)) }
            "reject" -> endLocal("Declined")
            "busy" -> endLocal("On another call")
            "end" -> endLocal(if (s.phase == Phase.INCOMING) "Missed call" else "Call ended")
        }
    }

    fun onRemoteEndPush(callId: String) = scope.launch {
        if (_state.value?.callId == callId) endLocal(if (_state.value?.phase == Phase.INCOMING) "Missed call" else "Call ended")
        else seenCalls.add(callId)
    }

    // ---------------------------------------------------------------- controls
    fun toggleMute() = scope.launch {
        val m = !(_state.value?.muted ?: return@launch)
        audioTrack?.setEnabled(!m); update { it.copy(muted = m) }
    }

    fun toggleSpeaker() = scope.launch {
        val on = !(_state.value?.speaker ?: return@launch)
        routeAudio(on); update { it.copy(speaker = on) }
    }

    fun toggleCamera() = scope.launch {
        val s = _state.value ?: return@launch
        if (!s.video) return@launch
        val on = !s.camOn
        videoTrack?.setEnabled(on)
        if (on) runCatching { capturer?.startCapture(1280, 720, 30) } else runCatching { capturer?.stopCapture() }
        update { it.copy(camOn = on) }
        signal("media") { put("on", on) }
    }

    fun switchCamera() = scope.launch {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { update { it.copy(frontCam = front) } }
            override fun onCameraSwitchError(e: String?) {}
        })
    }

    // ---------------------------------------------------------------- webrtc setup
    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= 29)
            .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= 29)
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun setupMedia(video: Boolean) {
        val f = ensureFactory()
        if (audioTrack == null) {
            val c = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = f.createAudioSource(c)
            audioTrack = f.createAudioTrack("audio0", audioSource)
        }
        if (video && videoTrack == null) {
            val en = Camera2Enumerator(app)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
            val cap = en.createCapturer(name, null)
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoSource = f.createVideoSource(false)
            cap.initialize(surfaceHelper, app, videoSource!!.capturerObserver)
            cap.startCapture(1280, 720, 30)
            capturer = cap
            videoTrack = f.createVideoTrack("video0", videoSource)
            localVideo.value = videoTrack
        }
    }

    private suspend fun loadIce(): List<PeerConnection.IceServer> {
        iceServers?.let { return it }
        val list = ArrayList<PeerConnection.IceServer>()
        runCatching {
            val arr = Api.get("/ice").getJSONArray("iceServers")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urls = o.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } }
                val b = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) b.setUsername(o.getString("username")).setPassword(o.getString("credential"))
                list.add(b.createIceServer())
            }
        }
        if (list.isEmpty()) list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        else iceServers = list
        return list
    }

    private suspend fun createPeer() {
        if (pc != null) return
        val f = ensureFactory()
        val cfg = PeerConnection.RTCConfiguration(loadIce()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        remoteDescSet = false
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                scope.launch { signal("ice") { put("c", JSONObject().put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("sdp", c.sdp)) } }
            }
            override fun onIceConnectionChange(st: PeerConnection.IceConnectionState) {
                scope.launch {
                    when (st) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                            timeoutJob?.cancel()
                            update { it.copy(phase = Phase.CONNECTED, connectedAt = if (it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt) }
                            startService()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            if (_state.value?.phase == Phase.CONNECTED) update { it.copy(phase = Phase.RECONNECTING) }
                            timeoutJob?.cancel()
                            timeoutJob = scope.launch { delay(15_000); if (_state.value?.phase == Phase.RECONNECTING) hangupInternal("Connection lost") }
                        }
                        PeerConnection.IceConnectionState.FAILED -> hangupInternal("Connection lost")
                        else -> {}
                    }
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val t = transceiver.receiver.track()
                if (t is VideoTrack) { t.setEnabled(true); remoteVideo.value = t }
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onAddStream(p0: MediaStream) {}
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onDataChannel(p0: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("peer connection failed")
        audioTrack?.let { pc!!.addTrack(it, listOf("s0")) }
        videoTrack?.let { pc!!.addTrack(it, listOf("s0")) }
    }

    private fun sdpConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (_state.value?.video == true) "true" else "false"))
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { c ->
        createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) { c.resume(d) }
            override fun onCreateFailure(e: String?) { c.resumeWithException(Exception(e)) }
        }, sdpConstraints())
    }
    private suspend fun PeerConnection.createAnswerAwait(): SessionDescription = suspendCancellableCoroutine { c ->
        createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) { c.resume(d) }
            override fun onCreateFailure(e: String?) { c.resumeWithException(Exception(e)) }
        }, sdpConstraints())
    }
    private suspend fun PeerConnection.setLocalAwait(d: SessionDescription): Unit = suspendCancellableCoroutine { c ->
        setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { c.resume(Unit) }
            override fun onSetFailure(e: String?) { c.resumeWithException(Exception(e)) }
        }, d)
    }
    private suspend fun PeerConnection.setRemoteAwait(d: SessionDescription): Unit = suspendCancellableCoroutine { c ->
        setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { c.resume(Unit) }
            override fun onSetFailure(e: String?) { c.resumeWithException(Exception(e)) }
        }, d)
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    // ---------------------------------------------------------------- audio routing
    private val am get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun setupAudioMode(speaker: Boolean) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        routeAudio(speaker)
    }

    private fun routeAudio(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = am.availableCommunicationDevices
            val headset = devs.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET) }
            val target = if (speaker) devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                         else headset ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            target?.let { runCatching { am.setCommunicationDevice(it) } }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speaker
        }
    }

    private fun startRingback() {
        runCatching { tone?.release(); tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }
    }
    private fun stopRingback() { runCatching { tone?.stopTone(); tone?.release() }; tone = null }

    // ---------------------------------------------------------------- teardown
    private fun endLocal(reason: String) {
        val s = _state.value ?: return
        if (s.phase == Phase.ENDED) return
        timeoutJob?.cancel()
        stopRingback()
        _state.value = s.copy(phase = Phase.ENDED, endReason = reason)
        runCatching { pc?.close() }; runCatching { pc?.dispose() }; pc = null
        runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }; capturer = null
        localVideo.value = null; remoteVideo.value = null
        runCatching { videoTrack?.dispose() }; videoTrack = null
        runCatching { videoSource?.dispose() }; videoSource = null
        runCatching { surfaceHelper?.dispose() }; surfaceHelper = null
        runCatching { audioTrack?.dispose() }; audioTrack = null
        runCatching { audioSource?.dispose() }; audioSource = null
        pendingIce.clear(); remoteDescSet = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
            am.mode = AudioManager.MODE_NORMAL
        }
        Realtime.release("call")
        Repo.scope.launch { delay(1500); Repo.sync() }
        scope.launch {
            delay(1800)
            if (_state.value?.callId == s.callId) _state.value = null
        }
    }

    // ---------------------------------------------------------------- service / ui
    private fun startService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, CallService::class.java)) }
            .onFailure { Log.w(TAG, "service: ${it.message}") }
    }

    fun launchUi(action: String?) {
        val i = Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action?.let { i.action = it }
        runCatching { app.startActivity(i) }
    }
}
