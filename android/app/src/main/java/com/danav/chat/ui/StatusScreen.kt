package com.danav.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.danav.chat.data.*
import com.danav.chat.ui.theme.Brand
import com.danav.chat.ui.theme.LocalChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statuses by Repo.db.observeStatuses().collectAsStateWithLifecycle(emptyList())
    val myProfile by Repo.me.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<StoryStatus?>(null) }
    var textDialog by remember { mutableStateOf(false) }
    var textStatus by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch {
            working = true
            runCatching {
                val prepared = MediaUtils.prepareImage(context, it)
                try {
                    val response = Api.uploadToCloudinary(prepared.file, "image", prepared.mime) {}
                    val media = Media(response.getString("secure_url"), response.optString("public_id"), prepared.w, prepared.h, size = response.optLong("bytes", prepared.file.length()), mime = prepared.mime)
                    Repo.publishStatus("image", media, "")
                } finally { prepared.file.delete() }
            }.onFailure { error = it.message ?: "Photo upload failed" }
            working = false
        } }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch {
            working = true
            runCatching {
                val prepared = MediaUtils.prepareVideo(context, it)
                try {
                    val response = Api.uploadToCloudinary(prepared.file, "video", prepared.mime) {}
                    val media = Media(response.getString("secure_url"), response.optString("public_id"), prepared.w, prepared.h, prepared.duration, response.optLong("bytes", prepared.file.length()), mime = prepared.mime)
                    Repo.publishStatus("video", media, "")
                } finally { prepared.file.delete() }
            }.onFailure { error = it.message ?: "Video upload failed" }
            working = false
        } }
    }
    LaunchedEffect(Unit) { runCatching { Repo.refreshStatuses() } }
    val myStatuses = statuses.filter { it.user.id == Repo.meId }.sortedByDescending { it.createdAt }
    val others = statuses.filter { it.user.id != Repo.meId }.groupBy { it.user.id }.values
        .map { it.sortedByDescending(StoryStatus::createdAt) }.sortedByDescending { it.first().createdAt }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Updates", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
                Text("Statuses disappear after 24 hours", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scope.launch { runCatching { Repo.refreshStatuses() } } }) { Icon(Icons.Filled.Refresh, "Refresh statuses") }
            IconButton(onClick = { textDialog = true }) { Icon(Icons.Filled.Edit, "Write a status", tint = Brand) }
        }
        AnimatedVisibility(working) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand) }
        AnimatedVisibility(error != null) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(error.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
            item(key = "my-status") {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("My status", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (myStatuses.isNotEmpty()) TextButton(onClick = { selected = myStatuses.first() }) { Text("View ${myStatuses.size}", color = Brand) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "add-status") {
                            AddStatusTile(
                                user = myProfile,
                                onPhoto = { imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                onText = { textDialog = true },
                                onVideo = { videoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                                hasStatus = myStatuses.isNotEmpty(),
                                onOpen = { if (myStatuses.isNotEmpty()) selected = myStatuses.first() },
                            )
                        }
                        items(others, key = { it.first().user.id }) { updates ->
                            StatusTile(updates, onClick = { selected = updates.first() })
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(.7f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent updates", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("${others.size} contacts", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            if (others.isEmpty()) {
                item(key = "empty-status") {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(68.dp).clip(CircleShape).background(Brand.copy(.10f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(30.dp), tint = Brand)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("You're all caught up", fontWeight = FontWeight.SemiBold)
                        Text("New updates from your contacts will show here.", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            } else {
                items(others, key = { "recent-${it.first().user.id}" }) { updates ->
                    RecentStatusRow(updates, onClick = { selected = updates.first() })
                }
            }
        }
    }
    if (textDialog) AlertDialog(
        onDismissRequest = { textDialog = false },
        title = { Text("Write a status") },
        text = { OutlinedTextField(textStatus, { textStatus = it.take(500) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, shape = RoundedCornerShape(16.dp), label = { Text("What's on your mind?") }) },
        confirmButton = { TextButton(enabled = textStatus.isNotBlank() && !working, onClick = {
            scope.launch { working = true; runCatching { Repo.publishStatus("text", Media(url = "", mime = "text/plain"), textStatus.trim()); textStatus = ""; textDialog = false }.onFailure { error = it.message }; working = false }
        }) { Text("Share", color = Brand) } },
        dismissButton = { TextButton(onClick = { textDialog = false }) { Text("Cancel") } },
    )
    selected?.let { StatusViewer(it, onClose = { selected = null }) }
}

@Composable
private fun AddStatusTile(user: User?, onPhoto: () -> Unit, onText: () -> Unit, onVideo: () -> Unit, hasStatus: Boolean, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.width(116.dp).height(174.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().clickable(onClick = if (hasStatus) onOpen else onPhoto).padding(11.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(68.dp).border(2.dp, if (hasStatus) Brand else MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(4.dp)) { Avatar(user, 58) }
                    Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(28.dp).clip(CircleShape).background(Brand).border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (hasStatus) Icons.Filled.MoreHoriz else Icons.Filled.Add, null, Modifier.size(19.dp), tint = Color.White)
                    }
                }
                Text(if (hasStatus) "My status" else "Add status", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (hasStatus) "Tap to view" else "Share an update", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.MoreVert, "Status options", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Filled.Image, null) }, onClick = { menu = false; onPhoto() })
                    DropdownMenuItem(text = { Text("Text") }, leadingIcon = { Icon(Icons.Filled.TextFields, null) }, onClick = { menu = false; onText() })
                    DropdownMenuItem(text = { Text("Video") }, leadingIcon = { Icon(Icons.Filled.Videocam, null) }, onClick = { menu = false; onVideo() })
                }
            }
        }
    }
}

@Composable
private fun StatusTile(statuses: List<StoryStatus>, onClick: () -> Unit) {
    val latest = statuses.first()
    val image = latest.media?.let { if (latest.type == "video") it.thumb(360) else it.url }?.takeIf { latest.type == "image" || latest.type == "video" }
    Card(Modifier.width(116.dp).height(174.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), elevation = CardDefaults.cardElevation(2.dp)) {
        Box(Modifier.fillMaxSize()) {
            if (!image.isNullOrBlank()) AsyncImage(image, "${latest.user.display}'s status", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF146B58), Color(0xFF172D37)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.12f), Color.Transparent, Color.Black.copy(.68f)))))
            Box(Modifier.align(Alignment.TopStart).padding(9.dp).size(43.dp).border(2.dp, if (statuses.any { !it.viewed }) Brand else Color.White.copy(.65f), CircleShape).padding(3.dp)) {
                Avatar(latest.user, 33)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 11.dp)) {
                Text(latest.user.display, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (statuses.size > 1) "${statuses.size} updates" else formatRelative(latest.createdAt), color = Color.White.copy(.82f), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RecentStatusRow(statuses: List<StoryStatus>, onClick: () -> Unit) {
    val user = statuses.first().user
    val unseen = statuses.any { !it.viewed }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(58.dp).border(2.dp, if (unseen) Brand else MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(3.dp)) { Avatar(user, 50) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(user.display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (unseen) "${statuses.size} new update${if (statuses.size == 1) "" else "s"}" else "${statuses.size} updates", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(formatRelative(statuses.maxOf { it.createdAt }), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
private fun StatusVideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = false; setShutterBackgroundColor(android.graphics.Color.BLACK) } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun StatusViewer(initial: StoryStatus, onClose: () -> Unit) {
    val all by Repo.db.observeStatuses().collectAsStateWithLifecycle(emptyList())
    var current by remember(initial.id) { mutableStateOf(initial) }
    var progress by remember { mutableFloatStateOf(0f) }
    val mine = current.user.id == Repo.meId
    val mediaUrl = current.media?.url
    fun nextStatus(): StoryStatus? = all.filter { it.user.id == current.user.id }.sortedBy { it.createdAt }.firstOrNull { it.createdAt > current.createdAt }
    LaunchedEffect(current.id) {
        progress = 0f
        if (!mine) runCatching { Repo.viewStatus(current.id) }
        val duration = if (current.type == "video") ((current.media?.duration ?: 0L).coerceIn(5_000L, 30_000L)) else 5_000L
        val steps = (duration / 50).toInt()
        repeat(steps) { delay(50); progress = (it + 1).toFloat() / steps }
        nextStatus()?.let { current = it } ?: onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (current.type == "image" && !mediaUrl.isNullOrBlank()) AsyncImage(mediaUrl, "Status", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            else if (current.type == "video" && !mediaUrl.isNullOrBlank()) StatusVideoPlayer(mediaUrl)
            else if (current.type == "video") Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PlayCircle, null, Modifier.size(68.dp), tint = Color.White); Text("Video unavailable", color = Color.White)
            } else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF138A61), Color(0xFF103D36)))), contentAlignment = Alignment.Center) {
                Text(current.caption, Modifier.padding(35.dp), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
            // Tap zones mimic the familiar previous/next story gestures.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { all.filter { it.user.id == current.user.id }.sortedByDescending { it.createdAt }.firstOrNull { it.createdAt < current.createdAt }?.let { current = it } })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { nextStatus()?.let { current = it } ?: onClose() })
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 14.dp), color = Color.White, trackColor = Color.White.copy(.35f))
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 28.dp, start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(current.user, 38); Spacer(Modifier.width(9.dp))
                Column { Text(current.user.display, color = Color.White, fontWeight = FontWeight.SemiBold); Text(formatRelative(current.createdAt), color = Color.White.copy(.8f), fontSize = 11.sp) }
                Spacer(Modifier.weight(1f))
                if (mine) IconButton(onClick = { Repo.scope.launch { runCatching { Repo.deleteStatus(current.id) }; onClose() } }) { Icon(Icons.Filled.Delete, "Delete status", tint = Color.White) }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            }
            if (current.caption.isNotBlank() && current.type != "text") Text(current.caption, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).background(Color.Black.copy(.45f), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White)
        }
    }
}
