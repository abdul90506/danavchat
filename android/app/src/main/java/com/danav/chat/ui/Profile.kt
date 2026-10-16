package com.danav.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danav.chat.data.*
import com.danav.chat.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val me by Repo.me.collectAsStateWithLifecycle()
    val theme by Repo.themeMode.collectAsStateWithLifecycle()
    var showEdit by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch { busy = true; runCatching { val url = Repo.uploadAvatar(it); Repo.updateProfile(avatar = url) }.onFailure { e -> error = e.message }; busy = false } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, null) }
            }
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(me, 78)
                    Box(Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(Brand).clickable { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Edit, null, Modifier.size(15.dp), tint = Color.White) }
                }
                Spacer(Modifier.width(15.dp)); Column(Modifier.weight(1f)) {
                    Text(me?.display ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("@${me?.username ?: ""}", color = Brand, fontSize = 14.sp)
                    Text(me?.email ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                IconButton(onClick = { showEdit = true }) { Icon(Icons.Filled.Edit, "Edit", tint = Brand) }
            }
            if (busy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(24.dp))
            ProfileInfo(Icons.Filled.Info, "About", me?.about ?: "", Brand)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("APPEARANCE", Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice("System", theme == 0) { Repo.setTheme(0) }
                ThemeChoice("Light", theme == 1) { Repo.setTheme(1) }
                ThemeChoice("Dark", theme == 2) { Repo.setTheme(2) }
            }
            Spacer(Modifier.height(18.dp)); HorizontalDivider()
            SettingsRow(Icons.Filled.Block, "Blocked contacts", "Manage who can reach you") { showBlocked = true }
            SettingsRow(Icons.Filled.Storage, "Storage & data", "Media is compressed before upload") { android.widget.Toast.makeText(ctx, "Media is securely stored in your private account.", android.widget.Toast.LENGTH_SHORT).show() }
            SettingsRow(Icons.Filled.Security, "Privacy", "Manage blocked contacts and visibility") { android.widget.Toast.makeText(ctx, "Messages use encrypted HTTPS/WSS in transit. Chats are not end-to-end encrypted yet.", android.widget.Toast.LENGTH_LONG).show() }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { Repo.logout(); onDismiss() }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(9.dp)); Text("Sign out", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp)); Text("Danav Chat  ·  Version 1.0", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
    if (showEdit) EditProfileDialog(onDismiss = { showEdit = false })
    if (showBlocked) BlockedDialog(onDismiss = { showBlocked = false })
}

@Composable
private fun ProfileInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) { Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = tint); Spacer(Modifier.width(18.dp)); Column { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Spacer(Modifier.height(3.dp)); Text(value, fontSize = 15.sp) } }
}

@Composable
private fun ThemeChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) }, leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null)
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EditProfileDialog(onDismiss: () -> Unit) {
    val me by Repo.me.collectAsStateWithLifecycle(); val scope = rememberCoroutineScope()
    var name by remember(me) { mutableStateOf(me?.name ?: "") }; var about by remember(me) { mutableStateOf(me?.about ?: "") }; var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit profile") }, text = { Column {
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(10.dp)); OutlinedTextField(about, { about = it.take(140) }, label = { Text("About") }, shape = RoundedCornerShape(14.dp), supportingText = { Text("${about.length}/140") })
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch { busy = true; runCatching { Repo.updateProfile(name = name, about = about) }.onSuccess { onDismiss() }.onFailure { err = it.message }; busy = false } }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun BlockedDialog(onDismiss: () -> Unit) {
    var users by remember { mutableStateOf<List<User>>(emptyList()) }; val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { users = Repo.blockedUsers() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Blocked contacts") }, text = {
        if (users.isEmpty()) Text("You haven't blocked anyone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { users.forEach { u -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(u, 40); Spacer(Modifier.width(10.dp)); Text(u.display, Modifier.weight(1f)); TextButton(onClick = { scope.launch { runCatching { Repo.setBlocked(u.id, false) }; users = users - u } }) { Text("Unblock") } } } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
