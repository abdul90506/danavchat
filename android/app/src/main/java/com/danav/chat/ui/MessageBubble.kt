package com.danav.chat.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.danav.chat.data.*
import com.danav.chat.ui.theme.*
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, selected: Boolean, albumMessages: List<Message> = listOf(m), onTap: () -> Unit, onLong: () -> Unit, onReply: () -> Unit, onForward: () -> Unit, onDeleteMe: () -> Unit, onDeleteAll: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val me = Repo.meId
    val mine = m.sender == me
    val colors = LocalChatColors.current
    val uploadProgress by Repo.uploadProgress.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var replyDelta by remember { mutableFloatStateOf(0f) }
    val replyOffset by androidx.compose.animation.core.animateFloatAsState(replyDelta.coerceAtLeast(0f), label = "reply")
    var imagePreview by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxWidth().animateContentSize(tween(160)), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(Modifier.padding(vertical = 1.dp).combinedClickable(onClick = { if (selected) onTap() }, onLongClick = { menu = true; onLong() }).pointerInput(m.id) {
            detectHorizontalDragGestures(onHorizontalDrag = { change, drag ->
                if ((mine && drag < 0) || (!mine && drag > 0)) { replyDelta = (replyDelta + drag).coerceIn(0f, 65f); change.consume() }
            }, onDragEnd = { if (replyDelta > 42f) onReply(); replyDelta = 0f })
        }, contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            if (replyOffset > 0) Icon(Icons.Filled.Reply, null, Modifier.align(Alignment.CenterStart).offset(x = 5.dp).size(19.dp), tint = Brand)
            Surface(
                modifier = Modifier.offset(x = if (replyOffset > 0) replyOffset.dp else 0.dp).widthIn(max = 328.dp).padding(horizontal = 3.dp)
                    .then(if (selected) Modifier.border(2.dp, Brand, RoundedCornerShape(16.dp)) else Modifier),
                shape = RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomStart = if (mine) 17.dp else 5.dp, bottomEnd = if (mine) 5.dp else 17.dp),
                color = if (selected) colors.selected else if (mine) colors.bubbleOut else colors.bubbleIn,
                shadowElevation = if (colors.isDark) 0.dp else .5.dp,
                onClick = { if (selected) onTap() },
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 5.dp)) {
                    if (m.replyTo != null) {
                        val quoted = Repo.db.message(m.replyTo)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (mine) colors.replyBgOut else colors.replyBgIn).padding(8.dp)) {
                            Box(Modifier.width(3.dp).height(34.dp).clip(CircleShape).background(Brand)); Spacer(Modifier.width(7.dp))
                            Column { Text(if (quoted?.sender == me) "You" else Repo.db.user(quoted?.sender ?: "")?.display ?: "Reply", color = Brand, fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Text(quoted?.body ?: quoted?.let { msgLabel(it) } ?: "Message", color = colors.bubbleText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    when (m.type) {
                        "image" -> {
                            if (albumMessages.size > 1) {
                                PhotoAlbumGrid(albumMessages, onOpen = { imagePreview = it })
                            } else {
                                val src = m.localPath?.takeIf { File(it).exists() }?.let { Uri.fromFile(File(it)).toString() } ?: m.media?.url
                                if (!src.isNullOrEmpty()) {
                                    val ratio = ((m.media?.w ?: 4).toFloat() / (m.media?.h ?: 3).coerceAtLeast(1)).coerceIn(.35f, 3.0f)
                                    BoxWithConstraints(Modifier.widthIn(max = 290.dp)) {
                                        val displayWidth = minOf(maxWidth, 360.dp * ratio)
                                        val displayHeight = displayWidth / ratio
                                        Box(Modifier.width(displayWidth).height(displayHeight).clip(RoundedCornerShape(10.dp)).clickable { imagePreview = src },contentAlignment=Alignment.Center){
                            AsyncImage(src, "Photo", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                            if(m.uploading) Surface(color=Color.Black.copy(.55f),shape=CircleShape){Box(Modifier.size(52.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(progress={uploadProgress[m.id]?:0f},modifier=Modifier.fillMaxSize().padding(4.dp),strokeWidth=3.dp,color=Color.White,trackColor=Color.White.copy(.25f));Text("${((uploadProgress[m.id]?:0f)*100).toInt()}%",fontSize=10.sp,color=Color.White,fontWeight=FontWeight.SemiBold)}}
                        }
                                    }
                                }
                            }
                            if (!m.body.isNullOrBlank()) Text(m.body, Modifier.padding(top = 5.dp), color = colors.bubbleText, fontSize = 14.sp)
                        }
                        "video" -> VideoMessage(m)
                        "audio" -> AudioMessage(m)
                        "file" -> FileMessage(m)
                        "deleted" -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Block, null, Modifier.size(14.dp), tint = colors.meta); Spacer(Modifier.width(5.dp)); Text("This message was deleted", color = colors.meta, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
                        else -> Text(m.body.orEmpty(), color = colors.bubbleText, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                    if (m.type != "deleted") Row(Modifier.align(Alignment.End).padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m.status == MsgStatus.FAILED) Text("Not sent · ", color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.clickable { onRetry() })
                        Text(timeOnly(m.createdAt), color = colors.meta, fontSize = 10.sp)
                        if (mine) { Spacer(Modifier.width(3.dp)); VerifiedTicks(m.status) }
                        if (m.uploading) { Spacer(Modifier.width(5.dp)); Text("${(Repo.uploadProgress.value[m.id] ?: 0f).times(100).toInt()}%", fontSize = 9.sp, color = colors.meta) }
                    }
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = { menu = false; onReply() }, leadingIcon = { Icon(Icons.Filled.Reply, null) })
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; m.body?.let { android.content.ClipData.newPlainText("message", it).also { cd -> (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(cd) } } }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; onForward() }, leadingIcon = { Icon(Icons.Filled.Forward, null) })
                DropdownMenuItem(text = { Text("Delete for me") }, onClick = { menu = false; onDeleteMe() }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                if (mine && System.currentTimeMillis() - m.createdAt < 2 * 24 * 3600_000) DropdownMenuItem(text = { Text("Delete for everyone") }, onClick = { menu = false; onDeleteAll() }, leadingIcon = { Icon(Icons.Filled.DeleteForever, null) })
            }
        }
    }
    imagePreview?.let { src ->
        Dialog(onDismissRequest = { imagePreview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { imagePreview = null }, contentAlignment = Alignment.Center) {
                AsyncImage(src, "Photo preview", Modifier.fillMaxWidth().fillMaxHeight(.88f), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                IconButton(onClick = { imagePreview = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun PhotoAlbumGrid(messages: List<Message>, onOpen: (String) -> Unit) {
    val shown = messages.take(4)
    Column(Modifier.widthIn(max = 286.dp).clip(RoundedCornerShape(10.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { colIndex, m ->
                    val index = rowIndex * 2 + colIndex
                    val url = m.localPath?.takeIf { File(it).exists() }?.let { Uri.fromFile(File(it)).toString() } ?: m.media?.url.orEmpty()
                    Box(Modifier.weight(1f).height(116.dp).background(Color.Black).clickable { if (url.isNotEmpty()) onOpen(url) }, contentAlignment = Alignment.Center) {
                        if (url.isNotEmpty()) AsyncImage(url, "Photo ${index + 1}", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        if (index == 3 && messages.size > 4) Box(Modifier.fillMaxSize().background(Color.Black.copy(.55f)), contentAlignment = Alignment.Center) { Text("+${messages.size - 4}", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f).height(116.dp))
            }
        }
    }
}

@Composable
private fun VideoMessage(m: Message) {
    val ctx = LocalContext.current
    val uploadProgress by Repo.uploadProgress.collectAsStateWithLifecycle()
    val media = m.media
    val url = media?.url
    Box(Modifier.fillMaxWidth().widthIn(max = 290.dp).heightIn(min = 150.dp, max = 240.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable {
        if (!url.isNullOrBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setDataAndType(Uri.parse(url), "video/*")) }
    }, contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(media?.thumb(640), "Video", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(.55f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(32.dp), tint = Color.White) }
        if(m.uploading) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp),color=Color.Black.copy(.62f),shape=CircleShape){Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(progress={uploadProgress[m.id]?:0f},modifier=Modifier.size(19.dp),strokeWidth=2.dp,color=Color.White);Spacer(Modifier.width(8.dp));Text("Uploading ${(uploadProgress[m.id]?:0f).times(100).toInt()}%",color=Color.White,fontSize=11.sp)}}
        Text("${media?.w ?: 0} × ${media?.h ?: 0}", Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.White, fontSize = 10.sp)
    }
    if (!m.body.isNullOrBlank()) Text(m.body, Modifier.padding(top = 5.dp), color = LocalChatColors.current.bubbleText, fontSize = 14.sp)
}

@Composable
private fun AudioMessage(m: Message) {
    val audioId by AudioPlayer.playingId.collectAsStateWithLifecycle()
    val progress by AudioPlayer.progress.collectAsStateWithLifecycle()
    val playing by AudioPlayer.isPlaying.collectAsStateWithLifecycle()
    val position by AudioPlayer.position.collectAsStateWithLifecycle()
    val me by Repo.me.collectAsStateWithLifecycle()
    val mine = m.sender == Repo.meId
    val voiceUser = if (mine) me else Repo.db.user(m.sender)
    val thisAudio = audioId == m.id
    val media = m.media
    val src = m.localPath?.takeIf { File(it).exists() } ?: media?.url.orEmpty()
    val bars = media?.wave.orEmpty().ifEmpty { List(30) { 18 + ((it * 29 + 11) % 63) } }.take(36)
    Row(Modifier.widthIn(min = 240.dp, max = 304.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mine) {
            Avatar(voiceUser, 39)
            Spacer(Modifier.width(7.dp))
        }
        IconButton(
            onClick = { if (src.isNotEmpty()) AudioPlayer.toggle(m.id, src) },
            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (mine) Brand.copy(.88f) else Brand),
        ) {
            Icon(if (thisAudio && playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (thisAudio && playing) "Pause voice note" else "Play voice note", tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            val smoothProgress by androidx.compose.animation.core.animateFloatAsState(if (thisAudio) progress else 0f, animationSpec = tween(100), label = "voice-progress")
            Row(Modifier.fillMaxWidth().height(34.dp).pointerInput(m.id, src) {
                detectTapGestures { point -> if (src.isNotBlank()) AudioPlayer.toggle(m.id, src, (point.x / size.width).coerceIn(0f, 1f)) }
            }, verticalAlignment = Alignment.CenterVertically) {
                bars.forEachIndexed { index, height ->
                    val active = thisAudio && index < (smoothProgress * bars.size).toInt()
                    Box(Modifier.weight(1f).padding(horizontal = .7.dp).height((5 + height.coerceIn(8, 100) * .27f).dp).clip(CircleShape).background(if (active) Brand else LocalChatColors.current.meta.copy(.62f)))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(if (thisAudio && playing) position else media?.duration ?: 0), color = LocalChatColors.current.meta, fontSize = 10.sp)
                if (m.uploading) Text("Sending…", color = LocalChatColors.current.meta, fontSize = 9.sp)
            }
        }
        if (!mine) {
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(37.dp), contentAlignment = Alignment.BottomEnd) {
                Avatar(voiceUser, 35)
                Icon(Icons.Filled.Mic, null, Modifier.size(13.dp).offset(x = 2.dp, y = 2.dp).clip(CircleShape).background(LocalChatColors.current.bubbleIn).padding(1.dp), tint = Brand)
            }
        }
    }
}

@Composable
private fun FileMessage(m: Message) {
    val ctx = LocalContext.current
    val name = m.media?.name ?: m.body ?: "Document"
    Row(Modifier.widthIn(min = 200.dp, max = 285.dp).clickable {
        val url = m.media?.url ?: return@clickable
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setDataAndType(Uri.parse(url), m.media.mime ?: "application/octet-stream")) }
    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brand.copy(.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Description, null, tint = Brand) }
        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(name, color = LocalChatColors.current.bubbleText, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${m.media?.mime ?: "File"} · ${formatBytes(m.media?.size ?: 0)}", color = LocalChatColors.current.meta, fontSize = 11.sp) }
        Icon(Icons.Filled.Download, null, tint = LocalChatColors.current.meta)
    }
}
