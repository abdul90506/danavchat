package com.danav.chat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danav.chat.data.Message
import com.danav.chat.data.Repo
import com.danav.chat.data.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ForwardDialog(message: Message, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(Repo.db.users().filter { it.id != Repo.meId }) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        if (query.isBlank()) { results = Repo.db.users().filter { it.id != Repo.meId }; return@LaunchedEffect }
        delay(250); loading = true
        runCatching { Repo.search(query) }.onSuccess { results = it.filter { u -> u.id != Repo.meId } }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward message") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp), decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search people…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                }
                Spacer(Modifier.height(10.dp))
                if (loading) Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                else if (results.isEmpty()) Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) { Text("No contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(results, key = { it.id }) { u ->
                        Row(Modifier.fillMaxWidth().clickable {
                            Repo.cacheUser(u); Repo.forward(u.id, message); onDismiss()
                        }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u, 40); Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(u.display); Text("@${u.username ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; RadioButton(selected = false, onClick = { Repo.cacheUser(u); Repo.forward(u.id, message); onDismiss() })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
