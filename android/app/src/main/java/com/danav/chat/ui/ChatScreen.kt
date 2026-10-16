package com.danav.chat.ui

import android.Manifest
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.danav.chat.data.*
import com.danav.chat.call.CallManager
import com.danav.chat.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerId: String, onBack: () -> Unit, onProfile: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val userFlow = remember(peerId) { Repo.db.observeUser(peerId) }
    val cached by userFlow.collectAsStateWithLifecycle(Repo.db.user(peerId))
    var liveUser by remember { mutableStateOf<User?>(null) }
    val user = liveUser ?: cached ?: User(peerId, null, "Danav user")
    val msgs by Repo.db.observeMessages(peerId, 600).collectAsStateWithLifecycle(emptyList())
    val albums = remember(msgs) { msgs.filter { it.media?.albumId != null }.groupBy { it.media!!.albumId!! } }
    val typingMap by Realtime.typing.collectAsStateWithLifecycle()
    val presenceMap by Realtime.presence.collectAsStateWithLifecycle()
    val isTyping = typingMap[peerId]?.first
    val presence = presenceMap[peerId]
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<Message?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardMessage by remember { mutableStateOf<Message?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var blockConfirm by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordLocked by remember { mutableStateOf(false) }
    var cancelRecording by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) }
    val recElapsed by recorder.elapsed.collectAsStateWithLifecycle()
    val amplitude by recorder.amplitude.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val openGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) {
            val album = if (uris.size > 1) UUID.randomUUID().toString() else null
            uris.forEachIndexed { index, uri -> Repo.sendMedia(peerId, "image", uri, null, if (index == 0) text.takeIf { it.isNotBlank() } else null, reply?.id, albumId = album) }
            text = ""; reply = null
        }
    }
    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { Repo.sendMedia(peerId, "video", it, null, null, reply?.id); reply = null } }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Repo.sendMedia(peerId, "file", it, null, null, reply?.id); reply = null } }
    val startRecording = {
        if (!recording) { recording = recorder.start(); recordLocked = false; cancelRecording = false; if (recording) Repo.typing(peerId, "recording") }
    }
    val stopAndSendRecording = {
        val result = recorder.stop(); recording = false; recordLocked = false; cancelRecording = false; Repo.typing(peerId, "")
        result?.let { (file, dur, wave) -> Repo.sendMedia(peerId, "audio", null, Prepared(file, "audio/mp4", duration = dur), null, reply?.id, wave); reply = null }
    }
    val discardRecording = { recorder.cancel(); recording = false; recordLocked = false; cancelRecording = false; Repo.typing(peerId, "") }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRecording() }
    val recordingNow by rememberUpdatedState(recording)
    val lockedNow by rememberUpdatedState(recordLocked)
    val startNow by rememberUpdatedState(startRecording)
    val sendNow by rememberUpdatedState(stopAndSendRecording)
    val discardNow by rememberUpdatedState(discardRecording)
    val cancelNow by rememberUpdatedState(cancelRecording)
    val setCancel by rememberUpdatedState<(Boolean) -> Unit> { cancelRecording = it }
    val setLocked by rememberUpdatedState<(Boolean) -> Unit> { recordLocked = it }
    var pendingVideoCall by remember { mutableStateOf(false) }
    val callPerms = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) CallManager.startOutgoing(peerId, user.display, user.avatar, pendingVideoCall)
    }
    fun startCall(video: Boolean) {
        val req = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (req.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) CallManager.startOutgoing(peerId, user.display, user.avatar, video)
        else { pendingVideoCall = video; callPerms.launch(req) }
    }
    val call = CallManager.state.collectAsStateWithLifecycle().value
    val recordScale by animateFloatAsState(if (recording) 1.07f else 1f, animationSpec = androidx.compose.animation.core.spring(stiffness = 500f), label = "record-button")

    LaunchedEffect(peerId) {
        Repo.openChat.value = peerId
        Realtime.hold("chat:$peerId")
        Repo.fetchUser(peerId).also { liveUser = it }
        Repo.subscribePresence(peerId)
        Repo.markChatRead(peerId)
        Repo.sync()
    }
    DisposableEffect(peerId) {
        onDispose { Repo.openChat.value = null; Realtime.release("chat:$peerId"); Repo.typing(peerId, ""); if (recordingNow) recorder.cancel() }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }
    LaunchedEffect(text) { Repo.typing(peerId, if (text.isNotBlank()) "typing" else "") }
    LaunchedEffect(call?.callId) { if (call?.peerId == peerId) CallManager.launchUi(null) }

    val connStatus = when {
        isTyping == "recording" -> "recording voice…"
        isTyping == "typing" -> "typing…"
        presence?.online == true -> "online"
        presence?.lastSeen != null && presence.lastSeen > 0 -> "last seen ${formatRelative(presence.lastSeen)}"
        else -> ""
    }

    Scaffold(containerColor = LocalChatColors.current.chatBg,
        topBar = {
            Row(Modifier.fillMaxWidth().height(62.dp).background(LocalChatColors.current.topBar).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = LocalChatColors.current.topBarContent) }
                if (selected.isNotEmpty()) {
                    Text("${selected.size} selected", Modifier.weight(1f).padding(start = 12.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = LocalChatColors.current.topBarContent)
                } else {
                    Avatar(user, 40, online = presence?.online == true, onClick = onProfile)
                    Column(Modifier.weight(1f).clickable { onProfile() }.padding(start = 11.dp)) {
                        Text(user.display, fontWeight = FontWeight.SemiBold, color = LocalChatColors.current.topBarContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(connStatus, color = if (presence?.online == true || isTyping != null) Brand else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { val m = msgs.find { it.id in selected }; if (m != null) reply = m; selected = emptySet() }) { Icon(Icons.Filled.Reply, "Reply") }
                    IconButton(onClick = { scope.launch { Repo.deleteForMe(selected); selected = emptySet() } }) { Icon(Icons.Filled.Delete, "Delete for me") }
                    IconButton(onClick = { scope.launch { msgs.filter { it.id in selected && it.sender == Repo.meId }.forEach { Repo.deleteForEveryone(it.id) }; selected = emptySet() } }) { Icon(Icons.Filled.DeleteForever, "Delete my selected messages for everyone", tint = MaterialTheme.colorScheme.error) }
                } else {
                    IconButton(onClick = { startCall(false) }) { Icon(Icons.Filled.Call, "Voice call", tint = Brand) }
                    IconButton(onClick = { startCall(true) }) { Icon(Icons.Filled.Videocam, "Video call", tint = Brand) }
                    Box { IconButton(onClick = { showMore = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(showMore, { showMore = false }) {
                            DropdownMenuItem(text = { Text("View profile") }, onClick = { showMore = false; onProfile() }, leadingIcon = { Icon(Icons.Filled.Person, null) })
                            DropdownMenuItem(text = { Text("Clear chat") }, onClick = { showMore = false; scope.launch { Repo.clearChat(peerId) } }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) })
                            DropdownMenuItem(text = { Text(if (user.blocked) "Unblock contact" else "Block contact") }, onClick = { showMore = false; blockConfirm = true }, leadingIcon = { Icon(Icons.Filled.Block, null) })
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().background(LocalChatColors.current.chatBg)) {
                if (recording) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.Red)); Spacer(Modifier.width(9.dp)); Text(if (recordLocked) "Recording locked · ${formatDuration(recElapsed)}" else if (cancelRecording) "Release to discard" else "Slide ← to cancel · ↑ to lock", color = if (cancelRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f)); Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) { repeat(24) { i -> Box(Modifier.width(3.dp).height((4 + amplitude * ((i * 7 % 13) + 5)).dp).clip(CircleShape).background(Brand)) } }
                    Spacer(Modifier.width(12.dp)); if (recordLocked) TextButton(onClick = discardRecording) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    FilledIconButton(onClick = {
                        stopAndSendRecording()
                    }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand)) { Icon(Icons.AutoMirrored.Filled.Send, "Send voice message", tint = Color.White) }
                }
                if (reply != null && !recording) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(38.dp).clip(CircleShape).background(Brand)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(if (reply!!.sender == Repo.meId) "You" else user.display, color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(reply!!.body ?: msgLabel(reply!!), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }
                    IconButton(onClick = { reply = null }) { Icon(Icons.Filled.Close, null) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Surface(shape = RoundedCornerShape(26.dp), color = LocalChatColors.current.inputBg, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(horizontal = 5.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
                            IconButton(onClick = { showEmoji = !showEmoji }, modifier = Modifier.size(42.dp)) { Icon(Icons.Filled.Mood, "Emoji", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            BasicTextField(text, { text = it.take(8000) }, Modifier.weight(1f).padding(vertical = 12.dp), textStyle = androidx.compose.ui.text.TextStyle(color = LocalChatColors.current.bubbleText, fontSize = 15.sp), cursorBrush = SolidColor(Brand), minLines = 1, maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), decorationBox = { inner -> Box { if (text.isBlank()) Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp); inner() } })
                            IconButton(onClick = { showAttach = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Filled.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { openGallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.size(42.dp)) { Icon(Icons.Filled.PhotoCamera, "Photo", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    if (text.isNotBlank()) FilledIconButton(onClick = { Repo.sendText(peerId, text, reply?.id); text = ""; reply = null }, modifier = Modifier.size(49.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand, contentColor = Color.White)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                    else FilledIconButton(onClick = {
                        if (recording) stopAndSendRecording()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                    }, modifier = Modifier.size(49.dp).graphicsLayer(scaleX = recordScale, scaleY = recordScale).pointerInput(peerId) {
                        var dragX = 0f
                        var dragY = 0f
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragX = 0f; dragY = 0f; setCancel(false); setLocked(false)
                                if (!recordingNow && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startNow()
                                else if (!recordingNow) micPerm.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onDrag = { change, amount ->
                                change.consume(); dragX += amount.x; dragY += amount.y
                                if (dragX < -85f && !lockedNow) setCancel(true)
                                if (dragY < -85f) setLocked(true)
                            },
                            onDragEnd = { if (recordingNow && !lockedNow) { if (cancelNow) discardNow() else sendNow() } },
                            onDragCancel = { if (recordingNow && !lockedNow) discardNow() },
                        )
                    }, shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording) MaterialTheme.colorScheme.error else Brand, contentColor = Color.White)) { Icon(if (recording) Icons.Filled.Mic else Icons.Filled.Mic, "Tap to record · hold and slide for quick voice note") }
                }
                AnimatedVisibility(showEmoji, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("😀","😂","🥹","😍","🥰","😊","😭","🙏","❤️","👍","🔥","🎉","😎","🤔","👀","💯").forEach { e -> Text(e, Modifier.clickable { text += e }.padding(5.dp), fontSize = 24.sp) }
                } }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            ChatWallpaper()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (msgs.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(LocalChatColors.current.systemChip).padding(12.dp)) { Text("🔒  Messages are protected in transit", fontSize = 12.sp, color = LocalChatColors.current.dateChipText) }
                            Spacer(Modifier.height(16.dp)); Text("Say hello to ${user.display} 👋", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(msgs, key = { _, m -> m.id }) { index, m ->
                    val album = m.media?.albumId?.let { aid -> albums[aid] } ?: listOf(m)
                    val isAlbumFirst = m.media?.albumId == null || album.firstOrNull()?.id == m.id
                    if (isAlbumFirst) {
                        if (index == 0 || !sameDay(msgs[index - 1].createdAt, m.createdAt)) DateChip(m.createdAt)
                        MessageBubble(m, selected = m.id in selected, albumMessages = album, onTap = { if (selected.isNotEmpty()) selected = if (m.id in selected) selected - m.id else selected + m.id }, onLong = { selected = selected + m.id }, onReply = { reply = m }, onForward = { forwardMessage = m }, onDeleteMe = { Repo.deleteForMe(album.map { it.id }) }, onDeleteAll = { album.filter { it.sender == m.sender }.forEach { Repo.deleteForEveryone(it.id) } }, onRetry = { Repo.retry(m) })
                    }
                }
            }
            AnimatedVisibility(listState.canScrollBackward && listState.canScrollForward && selected.isEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)) {
                Surface(onClick = { scope.launch { listState.animateScrollToItem(msgs.lastIndex.coerceAtLeast(0)) } }, shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 5.dp, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.KeyboardArrowDown, "Latest messages", tint = Brand) }
                }
            }
        }
    }
    if (showAttach) ModalBottomSheet(onDismissRequest = { showAttach = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp), horizontalArrangement = Arrangement.SpaceAround) {
            AttachmentOption("Gallery", Icons.Filled.Photo, Color(0xFFB967D6)) { showAttach = false; openGallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            AttachmentOption("Video", Icons.Filled.Videocam, Color(0xFFE25B72)) { showAttach = false; openVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            AttachmentOption("Document", Icons.Filled.Description, Color(0xFF6E9AE9)) { showAttach = false; openFile.launch(arrayOf("*/*")) }
            AttachmentOption("Contact", Icons.Filled.Person, Brand) { showAttach = false; Toast.makeText(context, "Use username search to share a contact", Toast.LENGTH_SHORT).show() }
        }
        Spacer(Modifier.height(25.dp))
    }
    forwardMessage?.let { ForwardDialog(it) { forwardMessage = null } }
    if (blockConfirm) AlertDialog(
        onDismissRequest = { blockConfirm = false },
        title = { Text(if (user.blocked) "Unblock ${user.display}?" else "Block ${user.display}?") },
        text = { Text(if (user.blocked) "They will be able to message and call you again." else "They won't be able to message or call you. Their messages will not be delivered.") },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { Repo.setBlocked(peerId, !user.blocked) }.onSuccess { liveUser = user.copy(blocked = !user.blocked) }
                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                    blockConfirm = false
                }
            }) { Text(if (user.blocked) "Unblock" else "Block", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { blockConfirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun AttachmentOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = click)) { Box(Modifier.size(56.dp).clip(CircleShape).background(color.copy(.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(25.dp)) }; Spacer(Modifier.height(8.dp)); Text(label, fontSize = 12.sp) }
}

@Composable
fun DateChip(time: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(color = LocalChatColors.current.dateChip, shape = RoundedCornerShape(9.dp), shadowElevation = 1.dp) { Text(dateLabel(time), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = LocalChatColors.current.dateChipText, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
    }
}
