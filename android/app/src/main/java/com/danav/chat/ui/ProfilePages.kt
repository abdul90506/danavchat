package com.danav.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.danav.chat.data.*
import com.danav.chat.call.CallManager
import com.danav.chat.ui.theme.Brand
import kotlinx.coroutines.launch

@Composable
fun ProfilePage(userId: String?, self: Boolean, onBack: () -> Unit, onChat: (String) -> Unit = {}, onCall: (User, Boolean) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val own by Repo.me.collectAsStateWithLifecycle()
    val cachedContact by remember(userId) { Repo.db.observeUser(userId.orEmpty()) }.collectAsStateWithLifecycle(userId?.let { Repo.db.user(it) })
    var loaded by remember(userId, self) { mutableStateOf<User?>(null) }
    val user = if (self) own ?: User("", null, "Your profile") else loaded ?: cachedContact ?: User(userId.orEmpty(), null, "Danav user")
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var blockedDialog by remember { mutableStateOf(false) }
    var blockConfirm by remember { mutableStateOf(false) }
    var photoOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch { busy = true; runCatching { val url = Repo.uploadAvatar(it); Repo.updateProfile(avatar = url) }.onFailure { error = it.message }; busy = false } }
    }
    var pendingVideo by remember { mutableStateOf(false) }
    val callPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) onCall(user, pendingVideo)
    }
    fun call(video: Boolean) {
        val perms = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) onCall(user, video)
        else { pendingVideo = video; callPerm.launch(perms) }
    }
    LaunchedEffect(userId, self) { if (!self && userId != null) loaded = Repo.fetchUser(userId) }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(if (self) "Profile" else "Contact info", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (self) IconButton(onClick = { edit = true }) { Icon(Icons.Filled.Edit, "Edit profile", tint = Brand) }
                else Box {
                    IconButton(onClick = { blockedDialog = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(blockedDialog, { blockedDialog = false }) {
                        DropdownMenuItem(text = { Text(if (user.blocked) "Unblock contact" else "Block contact") }, onClick = { blockedDialog = false; blockConfirm = true }, leadingIcon = { Icon(Icons.Filled.Block, null) })
                        DropdownMenuItem(text = { Text("Clear chat") }, onClick = { blockedDialog = false; Repo.clearChat(user.id); onBack() }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) })
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(250.dp).background(Brush.verticalGradient(listOf(Brand.copy(.16f), MaterialTheme.colorScheme.surface))), contentAlignment = Alignment.Center) {
                Box(Modifier.size(158.dp), contentAlignment = Alignment.BottomEnd) {
                    Avatar(user, 148, modifier = Modifier.align(Alignment.Center).clickable { photoOpen = !user.avatar.isNullOrBlank() })
                    if (self) Box(Modifier.size(42.dp).clip(CircleShape).background(Brand).clickable { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.CameraAlt, null, tint = Color.White) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(user.display, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("@${user.username ?: "username"}", color = Brand, fontSize = 15.sp)
                if (user.about.isNotBlank()) Text(user.about, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (self && user.email != null) Text(user.email, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (busy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(22.dp))
                if (!self) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { onChat(user.id) }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Filled.Chat, null); Spacer(Modifier.width(8.dp)); Text("Message") }
                    FilledTonalButton(onClick = { call(false) }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Filled.Call, null); Spacer(Modifier.width(8.dp)); Text("Voice call") }
                    FilledTonalButton(onClick = { call(true) }, modifier = Modifier.size(50.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(0.dp)) { Icon(Icons.Filled.Videocam, "Video call") }
                }
                Spacer(Modifier.height(24.dp))
                ProfileSection("ABOUT") { ProfileLine(Icons.Filled.Info, user.about.ifBlank { "No about information" }) }
                if (self) {
                    ProfileSection("ACCOUNT") {
                        ProfileLine(Icons.Filled.AlternateEmail, "Username", "@${user.username ?: ""}")
                        ProfileLine(Icons.Filled.Email, "Email", user.email ?: "")
                        ProfileLine(Icons.Filled.Security, "Privacy", "Messages are protected in transit")
                    }
                    ProfileSection("PREFERENCES") {
                        val theme by Repo.themeMode.collectAsStateWithLifecycle()
                        Text("Theme", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.Center) {
                            listOf("System" to 0, "Light" to 1, "Dark" to 2).forEach { (label, mode) -> FilterChip(theme == mode, { Repo.setTheme(mode) }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                        }
                        ProfileLine(Icons.Filled.Block, "Blocked contacts", "Manage blocked users", Modifier.clickable { blockedDialog = true })
                        ProfileLine(Icons.Filled.Storage, "Storage & data", "Compressed media uploads")
                    }
                    OutlinedButton(onClick = { Repo.logout(); onBack() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
                } else {
                    ProfileSection("SHARED") { ProfileLine(Icons.Filled.Image, "Media, links and documents", "Your shared chat items") }
                    OutlinedButton(onClick = { blockConfirm = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (user.blocked) Brand else MaterialTheme.colorScheme.error)) {
                        Icon(if (user.blocked) Icons.Filled.LockOpen else Icons.Filled.Block, null); Spacer(Modifier.width(8.dp)); Text(if (user.blocked) "Unblock ${user.display}" else "Block ${user.display}")
                    }
                }
                Spacer(Modifier.height(35.dp))
            }
        }
    }
    if (photoOpen && !user.avatar.isNullOrBlank()) AlertDialog(onDismissRequest = { photoOpen = false }, confirmButton = { TextButton(onClick = { photoOpen = false }) { Text("Close") } }, text = { AsyncImage(user.avatar, "Profile photo", Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) })
    if (edit) EditProfileDialog(onDismiss = { edit = false })
    if (blockConfirm) AlertDialog(onDismissRequest = { blockConfirm = false }, title = { Text(if (user.blocked) "Unblock contact?" else "Block contact?") }, text = { Text(if (user.blocked) "They can contact you again." else "Their messages and calls will not reach you.") }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { Repo.setBlocked(user.id, !user.blocked) }.onSuccess { loaded = user.copy(blocked = !user.blocked); Repo.db.setBlocked(user.id, !user.blocked) }; blockConfirm = false } }) { Text(if (user.blocked) "Unblock" else "Block", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { blockConfirm = false }) { Text("Cancel") } })
    if (blockedDialog && self) BlockedDialog(onDismiss = { blockedDialog = false })
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 13.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 10.dp)) {
        Text(title, Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun ProfileLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) { if (value == null) Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) else { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } }
        if (modifier != Modifier) Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
