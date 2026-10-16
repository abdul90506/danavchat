package com.danav.chat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.danav.chat.data.*
import com.danav.chat.ui.theme.Brand
import com.danav.chat.ui.theme.LocalChatColors
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(groupId: String, onBack: () -> Unit, onInfo: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by Repo.db.observeGroupMessages(groupId).collectAsStateWithLifecycle(emptyList())
    val groups by Repo.db.observeGroups().collectAsStateWithLifecycle(emptyList())
    val group = groups.firstOrNull { it.id == groupId }
    var text by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) }
    val elapsed by recorder.elapsed.collectAsStateWithLifecycle()
    val amplitude by recorder.amplitude.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { Repo.sendGroupMedia(groupId, "image", it) } }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { Repo.sendGroupMedia(groupId, "video", it) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Repo.sendGroupMedia(groupId, "file", it) } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) recording = recorder.start() }
    fun startVoiceNote() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = recorder.start()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun finishVoiceNote() {
        val recordingData = recorder.stop()
        recording = false
        recordingData?.let { (file, duration, wave) -> Repo.sendGroupMedia(groupId, "audio", Uri.fromFile(file), duration, wave) }
    }
    fun cancelVoiceNote() { recorder.cancel(); recording = false }
    LaunchedEffect(groupId) { runCatching { Repo.refreshGroups() } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty() && !listState.canScrollForward) listState.animateScrollToItem(messages.lastIndex) }
    val isRecording by rememberUpdatedState(recording)
    DisposableEffect(groupId) { onDispose { if (isRecording) recorder.cancel() } }

    Scaffold(
        containerColor = LocalChatColors.current.chatBg,
        topBar = {
            Row(Modifier.fillMaxWidth().height(64.dp).background(LocalChatColors.current.topBar).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LocalChatColors.current.topBarContent) }
                GroupAvatar(group, 42)
                Column(Modifier.weight(1f).clickable { showInfo = true }.padding(start = 11.dp)) {
                    Text(group?.name ?: "Group chat", fontWeight = FontWeight.SemiBold, color = LocalChatColors.current.topBarContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group?.memberCount ?: 0} members · tap for details", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "Group info", tint = Brand) }
                Box {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More options", tint = LocalChatColors.current.topBarContent) }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("Group info") }, leadingIcon = { Icon(Icons.Filled.Groups, null) }, onClick = { more = false; showInfo = true })
                        DropdownMenuItem(text = { Text("Search in conversation") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, onClick = { more = false })
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().background(LocalChatColors.current.chatBg)) {
                AnimatedVisibility(recording) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                        Spacer(Modifier.width(8.dp))
                        Text("Recording · ${formatDuration(elapsed)}", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            repeat(20) { index -> Box(Modifier.width(3.dp).height((4 + amplitude * (9 + index % 5)).dp).clip(CircleShape).background(Brand)) }
                        }
                        TextButton(onClick = ::cancelVoiceNote) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(25.dp), color = LocalChatColors.current.inputBg) {
                        Row(Modifier.padding(start = 5.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                            IconButton(onClick = { showEmoji = !showEmoji }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Mood, "Emoji", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            BasicTextField(text, { text = it.take(8000) }, Modifier.weight(1f).padding(vertical = 11.dp), textStyle = androidx.compose.ui.text.TextStyle(color = LocalChatColors.current.bubbleText, fontSize = 15.sp), maxLines = 5, decorationBox = { inner -> Box { if (text.isBlank()) Text("Message the group", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                            IconButton(onClick = { showAttach = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.PhotoCamera, "Photo", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    FilledIconButton(
                        onClick = {
                            if (recording) finishVoiceNote()
                            else if (text.isNotBlank()) { Repo.sendGroupText(groupId, text); text = "" }
                            else startVoiceNote()
                        },
                        modifier = Modifier.size(48.dp), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording) Color(0xFFE5484D) else Brand, contentColor = Color.White),
                    ) {
                        Icon(when { recording -> Icons.AutoMirrored.Filled.Send; text.isNotBlank() -> Icons.AutoMirrored.Filled.Send; else -> Icons.Filled.Mic }, if (recording || text.isNotBlank()) "Send" else "Record voice note")
                    }
                }
                AnimatedVisibility(showEmoji) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf("😀", "😂", "🥹", "😍", "😊", "😭", "🙏", "❤️", "👍", "🔥", "🎉", "😎", "🤔", "👀").forEach { emoji -> Text(emoji, Modifier.clickable { text += emoji }.padding(4.dp), fontSize = 23.sp) }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ChatWallpaper()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (messages.isEmpty()) item(key = "group-empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GroupAvatar(group, 72)
                        Spacer(Modifier.height(16.dp))
                        Text("Welcome to ${group?.name ?: "your group"}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("Share a message, photo or voice note with everyone.", Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    if (index == 0 || !sameDay(messages[index - 1].createdAt, message.createdAt)) DateChip(message.createdAt)
                    GroupMessageBubble(message, preview = { preview = it })
                }
            }
            AnimatedVisibility(listState.canScrollForward && messages.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp)) {
                Surface(onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } }, shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 5.dp, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.KeyboardArrowDown, "Latest messages", tint = Brand) }
                }
            }
        }
    }
    if (showInfo) GroupInfoDialog(groupId) { showInfo = false }
    if (showAttach) ModalBottomSheet(onDismissRequest = { showAttach = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Text("Share with the group", Modifier.padding(start = 22.dp, top = 3.dp), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp), horizontalArrangement = Arrangement.SpaceAround) {
            AttachmentMini("Gallery", Icons.Filled.Photo) { showAttach = false; photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            AttachmentMini("Video", Icons.Filled.Videocam) { showAttach = false; videoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            AttachmentMini("Document", Icons.Filled.Description) { showAttach = false; filePicker.launch(arrayOf("*/*")) }
        }
        Spacer(Modifier.height(22.dp))
    }
    preview?.let { url ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AsyncImage(url, "Photo preview", Modifier.fillMaxWidth().fillMaxHeight(.9f), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                IconButton(onClick = { preview = null }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Filled.Close, "Close preview", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun GroupMessageBubble(message: GroupMessage, preview: (String) -> Unit) {
    val context = LocalContext.current
    val me by Repo.me.collectAsStateWithLifecycle()
    val mine = message.sender == Repo.meId
    val sender = if (mine) me else Repo.db.user(message.sender)
    val colors = LocalChatColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!mine) { Avatar(sender, 29); Spacer(Modifier.width(6.dp)) }
        Surface(
            modifier = Modifier.widthIn(max = 318.dp).animateContentSize(animationSpec = tween(180)),
            color = if (mine) colors.bubbleOut else colors.bubbleIn,
            shape = RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomStart = if (mine) 17.dp else 5.dp, bottomEnd = if (mine) 5.dp else 17.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (!mine) Text(sender?.display ?: "Member", Modifier.padding(start = 2.dp, bottom = 3.dp), fontSize = 11.sp, color = Brand, fontWeight = FontWeight.SemiBold, maxLines = 1)
                when (message.type) {
                    "image" -> {
                        val url = message.media?.url
                        if (!url.isNullOrBlank()) AsyncImage(url, "Photo from ${sender?.display ?: "group member"}", Modifier.widthIn(max = 280.dp).heightIn(min = 100.dp, max = 260.dp).clip(RoundedCornerShape(12.dp)).clickable { preview(url) }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        if (!message.body.isNullOrBlank()) Text(message.body, Modifier.padding(top = 5.dp), color = colors.bubbleText, fontSize = 14.sp)
                    }
                    "video" -> {
                        val media = message.media
                        Box(Modifier.width(250.dp).height(165.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black).clickable { media?.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).setDataAndType(Uri.parse(it), "video/*")) } } }, contentAlignment = Alignment.Center) {
                            if (media != null) AsyncImage(media.thumb(480), "Video thumbnail", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(.52f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                        }
                    }
                    "audio" -> GroupAudioMessage(message)
                    "file" -> {
                        val media = message.media
                        Row(Modifier.widthIn(min = 220.dp, max = 270.dp).clip(RoundedCornerShape(12.dp)).clickable { media?.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).setDataAndType(Uri.parse(it), media.mime ?: "application/octet-stream")) } } }.background(colors.replyBgIn).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Brand.copy(.13f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Description, null, tint = Brand) }
                            Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(media?.name ?: message.body ?: "Document", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = colors.bubbleText); Text("${formatBytes(media?.size ?: 0)} · ${media?.mime ?: "File"}", color = colors.meta, fontSize = 10.sp, maxLines = 1) }
                        }
                    }
                    else -> Text(message.body.orEmpty(), Modifier.padding(horizontal = 2.dp, vertical = 2.dp), color = colors.bubbleText, fontSize = 15.sp, lineHeight = 21.sp)
                }
                Row(Modifier.align(Alignment.End).padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(timeOnly(message.createdAt), fontSize = 10.sp, color = colors.meta)
                    if (mine) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.DoneAll, null, Modifier.size(14.dp), tint = colors.meta) }
                }
            }
        }
        if (mine) Spacer(Modifier.width(2.dp))
    }
}

@Composable
private fun GroupAudioMessage(message: GroupMessage) {
    val id by AudioPlayer.playingId.collectAsStateWithLifecycle()
    val playing by AudioPlayer.isPlaying.collectAsStateWithLifecycle()
    val progress by AudioPlayer.progress.collectAsStateWithLifecycle()
    val position by AudioPlayer.position.collectAsStateWithLifecycle()
    val active = id == message.id
    val media = message.media
    Row(Modifier.widthIn(min = 220.dp, max = 275.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { media?.url?.let { AudioPlayer.toggle(message.id, it) } }, modifier = Modifier.size(38.dp).clip(CircleShape).background(Brand)) {
            Icon(if (active && playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play voice note", tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 7.dp)) {
            val smoothProgress by androidx.compose.animation.core.animateFloatAsState(if (active) progress else 0f, animationSpec = tween(100), label = "group-voice-progress")
            Row(Modifier.fillMaxWidth().height(31.dp).pointerInput(message.id, media?.url) {
                detectTapGestures { point -> media?.url?.let { AudioPlayer.toggle(message.id, it, (point.x / size.width).coerceIn(0f, 1f)) } }
            }, verticalAlignment = Alignment.CenterVertically) {
                val bars = media?.wave.orEmpty().ifEmpty { List(28) { 18 + (it * 19 % 58) } }
                bars.take(32).forEachIndexed { index, height -> Box(Modifier.weight(1f).padding(horizontal = .7.dp).height((5 + height.coerceIn(8, 100) * .25f).dp).clip(CircleShape).background(if (active && index < smoothProgress * bars.size) Brand else LocalChatColors.current.meta.copy(.65f))) }
            }
            Text(formatDuration(if (active && playing) position else media?.duration ?: 0), fontSize = 10.sp, color = LocalChatColors.current.meta)
        }
    }
}

@Composable
private fun AttachmentMini(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Brand.copy(.11f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Brand, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(7.dp)); Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GroupAvatar(group: ChatGroup?, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brand.copy(.12f)), contentAlignment = Alignment.Center) {
        if (!group?.avatar.isNullOrBlank()) AsyncImage(group?.avatar, group?.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(Icons.Filled.Groups, null, Modifier.size((size * .52f).dp), tint = Brand)
    }
}

@Composable
fun CreateGroupDialog(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { users = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        runCatching { Repo.search(query) }.onSuccess { users = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a group") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Add people by username") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Text("${selected.size} selected", Modifier.padding(vertical = 6.dp), color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                    users.forEach { user ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (user.id in selected) selected - user.id else selected + user.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(user, 36); Spacer(Modifier.width(9.dp)); Text(user.display, Modifier.weight(1f)); Checkbox(user.id in selected, { checked -> selected = if (checked) selected + user.id else selected - user.id })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = { onCreate(name, selected.toList()) }) { Text("Create", color = Brand) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
