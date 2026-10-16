package com.danav.chat.call

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danav.chat.data.User
import com.danav.chat.ui.Avatar
import com.danav.chat.ui.theme.Brand
import com.danav.chat.ui.theme.DanavTheme
import com.danav.chat.ui.formatDuration
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContent { DanavTheme { CallScreen(onClose = { finish() }) } }
    }
    companion object { const val ACTION_ACCEPT = "com.danav.chat.ACCEPT" }
}

@Composable
private fun CallScreen(onClose: () -> Unit) {
    val state by CallManager.state.collectAsStateWithLifecycle()
    val local by CallManager.localVideo.collectAsStateWithLifecycle()
    val remote by CallManager.remoteVideo.collectAsStateWithLifecycle()
    val s = state
    val ctx = LocalContext.current
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted -> if (granted.values.all { it }) CallManager.accept() }
    fun answerCall() {
        val required = if (s?.video == true) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (required.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) CallManager.accept() else perms.launch(required)
    }
    var elapsed by remember { mutableLongStateOf(0) }
    LaunchedEffect(s?.connectedAt) {
        while (true) { if (s?.connectedAt ?: 0L > 0) elapsed = System.currentTimeMillis() - (s?.connectedAt ?: 0L); delay(1000) }
    }
    BackHandler { if (s != null && s.phase != Phase.ENDED) CallManager.hangup() else onClose() }
    LaunchedEffect(s?.phase) { if (s == null) onClose() }
    if (s == null) { Box(Modifier.fillMaxSize().background(Color(0xFF0B141A))) ; return }

    val video = s.video
    Box(Modifier.fillMaxSize().then(if (video) Modifier.background(Color.Black) else Modifier.background(Brush.verticalGradient(listOf(Color(0xFF13563D), Color(0xFF101D21))))) ) {
        if (video && remote != null) {
            AndroidView(factory = { c -> SurfaceViewRenderer(c).apply { init(CallManager.eglBase.eglBaseContext, null); setEnableHardwareScaler(true); setMirror(false); remote?.addSink(this) } }, update = { if (remote != null) remote?.addSink(it) }, modifier = Modifier.fillMaxSize())
        }
        if (video && local != null && s.camOn) {
            AndroidView(factory = { c -> SurfaceViewRenderer(c).apply { init(CallManager.eglBase.eglBaseContext, null); setEnableHardwareScaler(true); setMirror(true); setZOrderMediaOverlay(true); local?.addSink(this) } }, update = { if (local != null) local?.addSink(it) }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 16.dp).size(width = 112.dp, height = 160.dp).clip(RoundedCornerShape(18.dp)))
        }
        if (!video || remote == null) {
            Column(Modifier.align(Alignment.Center).padding(bottom = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "ring")
                Box(Modifier.size((158 * pulse).dp).clip(CircleShape).background(Color.White.copy(.07f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(130.dp).clip(CircleShape).background(Color.White.copy(.10f)), contentAlignment = Alignment.Center) { Avatar(User(s.peerId, null, s.peerName, avatar = s.peerAvatar), 112) }
                }
                Spacer(Modifier.height(30.dp)); Text(s.peerName, fontSize = 27.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(11.dp)); Text(callSubtitle(s, elapsed), fontSize = 15.sp, color = Color.White.copy(.75f))
                if (s.phase == Phase.ENDED && s.endReason != null) { Spacer(Modifier.height(10.dp)); Text(s.endReason!!, color = Color.White.copy(.65f), fontSize = 13.sp) }
            }
        }
        if (video && remote != null) {
            Row(Modifier.align(Alignment.TopStart).padding(top = 58.dp, start = 18.dp).clip(CircleShape).background(Color.Black.copy(.38f)).padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF26D07C))); Spacer(Modifier.width(8.dp)); Text(callSubtitle(s, elapsed), color = Color.White, fontSize = 13.sp)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 26.dp).padding(bottom = 35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.phase != Phase.INCOMING && s.phase != Phase.ENDED) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), color = Color(0xFF101A1F).copy(.91f), tonalElevation = 3.dp) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            CallControl(if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (s.muted) "Unmute" else "Mute", s.muted) { CallManager.toggleMute() }
                            CallControl(if (s.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown, if (s.speaker) "Speaker" else "Earpiece", s.speaker) { CallManager.toggleSpeaker() }
                            if (video) CallControl(if (s.camOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Camera", !s.camOn) { CallManager.toggleCamera() }
                            if (video) CallControl(Icons.Filled.Cameraswitch, "Flip", false) { CallManager.switchCamera() }
                        }
                        Spacer(Modifier.height(20.dp))
                        FilledIconButton(onClick = { CallManager.hangup() }, modifier = Modifier.size(66.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE84057), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "End call", Modifier.size(29.dp)) }
                    }
                }
            } else if (s.phase == Phase.INCOMING) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), color = Color(0xFF101A1F).copy(.91f), tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { FilledIconButton(onClick = { CallManager.reject() }, modifier = Modifier.size(66.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE84057), contentColor = Color.White)) { Icon(Icons.Filled.CallEnd, "Decline", Modifier.size(28.dp)) }; Spacer(Modifier.height(9.dp)); Text("Decline", color = Color.White) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { FilledIconButton(onClick = { answerCall() }, modifier = Modifier.size(66.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand, contentColor = Color.White)) { Icon(if (video) Icons.Filled.Videocam else Icons.Filled.Call, "Answer", Modifier.size(28.dp)) }; Spacer(Modifier.height(9.dp)); Text("Answer", color = Color.White) }
                    }
                }
            } else if (s.phase == Phase.ENDED) {
                FilledTonalButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun CallControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (active) 1.06f else 1f, animationSpec = spring(stiffness = 500f), label = "call-control")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(54.dp).graphicsLayer(scaleX = scale, scaleY = scale), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (active) Color.White else Color.White.copy(.16f), contentColor = if (active) Color(0xFF172B29) else Color.White)) { Icon(icon, label) }
        Spacer(Modifier.height(7.dp)); Text(label, color = Color.White.copy(.85f), fontSize = 11.sp)
    }
}

private fun callSubtitle(s: CallUi, elapsed: Long): String = when (s.phase) {
    Phase.INCOMING -> if (s.video) "Incoming video call…" else "Incoming voice call…"
    Phase.CALLING -> "Calling…"
    Phase.RINGING -> "Ringing…"
    Phase.CONNECTING -> "Connecting…"
    Phase.RECONNECTING -> "Reconnecting…"
    Phase.CONNECTED -> formatDuration(elapsed)
    Phase.ENDED -> s.endReason ?: "Call ended"
}
