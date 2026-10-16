package com.danav.chat.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.danav.chat.BuildConfig
import com.danav.chat.call.CallManager
import com.danav.chat.call.Phase
import com.danav.chat.data.*
import com.danav.chat.ui.theme.*
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun DanavApp(openPeer: String?, openGroup: String?, onOpenHandled: () -> Unit, onGroupHandled: () -> Unit) {
    val me by Repo.me.collectAsStateWithLifecycle()
    val needsUsername by Repo.needsUsername.collectAsStateWithLifecycle()
    var peer by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("home") }
    var showProfile by remember { mutableStateOf(false) }
    var profileUser by remember { mutableStateOf<String?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openPeer) { openPeer?.let { id -> if (Repo.loggedIn) { Repo.fetchUser(id); peer = id; page = "home" }; onOpenHandled() } }
    LaunchedEffect(openGroup) { openGroup?.let { id -> if (Repo.loggedIn) { groupId=id; page="groups" }; onGroupHandled() } }
    LaunchedEffect(me?.id) { if (me != null) { Realtime.hold("fg"); Repo.sync(); Repo.registerFcm() } }
    val route = when {
        me == null -> "login"
        needsUsername -> "username"
        groupId != null -> "group:$groupId"
        profileUser != null -> "contact:$profileUser"
        showProfile -> "profile"
        peer != null -> "chat:$peer"
        else -> "home:$page"
    }
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            (fadeIn(tween(190)) + slideInHorizontally(tween(230)) { it / 18 }) togetherWith
                (fadeOut(tween(110)) + slideOutHorizontally(tween(150)) { -it / 28 })
        },
        label = "screen-transition",
    ) { destination ->
        when {
            destination == "login" -> LoginScreen { token -> Repo.loginWithGoogle(token) }
            destination == "username" -> UsernameScreen(me!!)
            destination.startsWith("group:") -> GroupChatScreen(destination.removePrefix("group:"), onBack = { groupId = null }, onInfo = { })
            destination.startsWith("contact:") -> {
                val id = destination.removePrefix("contact:")
                ProfilePage(userId = id, self = false, onBack = { profileUser = null }, onChat = { peerId -> profileUser = null; peer = peerId }, onCall = { u, video -> CallManager.startOutgoing(u.id, u.display, u.avatar, video) })
            }
            destination == "profile" -> ProfilePage(userId = null, self = true, onBack = { showProfile = false })
            destination.startsWith("chat:") -> {
                val id = destination.removePrefix("chat:")
                ChatScreen(peerId = id, onBack = { peer = null }, onProfile = { profileUser = id })
            }
            else -> HomeScreen(destination.removePrefix("home:"), { page = it }, { id -> peer = id; Repo.openChat.value = id }, { showProfile = true }, { profileUser = it.id }, { groupId = it })
        }
    }
}

@Composable
private fun LoginScreen(onLogin: suspend (String) -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val infinite = rememberInfiniteTransition(label = "login")
    val float by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Reverse), label = "float")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF087A4B), Color(0xFF11A866), Color(0xFFF6FAF7))))) {
        Box(Modifier.offset(x = (float * 30).dp, y = (-22).dp).size(260.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.White.copy(.07f)))
        Box(Modifier.offset(x = (-40).dp, y = (float * 40).dp).size(210.dp).align(Alignment.BottomStart).clip(CircleShape).background(Color(0xFF13B574).copy(.13f)))
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(92.dp).clip(RoundedCornerShape(30.dp)).background(Color.White.copy(.17f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Forum, null, Modifier.size(49.dp), tint = Color.White) }
            Spacer(Modifier.height(23.dp)); Text("Danav", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(8.dp)); Text("Your people. Your conversations.", color = Color.White.copy(.85f), fontSize = 15.sp)
            Spacer(Modifier.weight(.7f))
            Card(shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A little closer, every day.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp)); Text("One-to-one chats, groups and calls, made for the people who matter.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = { scope.launch { loading = true; error = null; runCatching { googleSignIn(ctx) }.onSuccess { token -> runCatching { onLogin(token) }.onFailure { error = it.message ?: "Could not sign in" } }.onFailure { error = if (it is GetCredentialException) "Google sign-in cancelled. Please try again." else it.message ?: "Could not sign in" }; loading = false } }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White)) {
                        if (loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Color.White) else { Icon(Icons.Filled.AccountCircle, null); Spacer(Modifier.width(10.dp)); Text("Continue with Google", fontWeight = FontWeight.SemiBold) }
                    }
                    AnimatedVisibility(error != null) { Text(error.orEmpty(), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.weight(1f)); Row(Modifier.padding(bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = Color.White.copy(.85f)); Spacer(Modifier.width(7.dp)); Text("Your chats, groups and calls in one place", color = Color.White.copy(.88f), fontSize = 12.sp) }
        }
    }
}

private suspend fun googleSignIn(context: Context): String {
    val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID).setAutoSelectEnabled(false).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val result = CredentialManager.create(context).getCredential(context as Activity, request)
    val cred = result.credential
    if (cred !is androidx.credentials.CustomCredential || cred.type != com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw IllegalStateException("Please choose a Google account")
    return com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(cred.data).idToken
}

@Composable
private fun UsernameScreen(me: User) {
    var username by remember { mutableStateOf("") }; var name by remember { mutableStateOf(me.name) }
    var checking by remember { mutableStateOf(false) }; var available by remember { mutableStateOf<Boolean?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf("Hey there! I am using Danav Chat.") }; val scope = rememberCoroutineScope()
    LaunchedEffect(username) { available = null; val c = username.lowercase().trim(); if (c.length >= 3 && c.matches(Regex("[a-z0-9_.]{3,20}"))) { delay(350); checking = true; runCatching { Repo.checkUsername(c).first }.onSuccess { available = it }; checking = false } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(68.dp)); Box(Modifier.size(88.dp).clip(CircleShape).background(Brand.copy(.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.AlternateEmail, null, Modifier.size(42.dp), tint = Brand) }
        Spacer(Modifier.height(24.dp)); Text("Make it yours", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text("Choose a username so friends can find you.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center); Spacer(Modifier.height(32.dp))
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Your name") }, leadingIcon = { Icon(Icons.Filled.Person, null) }, singleLine = true, shape = RoundedCornerShape(16.dp)); Spacer(Modifier.height(14.dp))
        OutlinedTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.take(20) }, Modifier.fillMaxWidth(), label = { Text("Username") }, leadingIcon = { Text("@", fontWeight = FontWeight.Bold, color = Brand) }, singleLine = true, shape = RoundedCornerShape(16.dp), supportingText = { Text(when { checking -> "Checking availability…"; available == true -> "Available"; available == false -> "Already taken — try another"; username.length in 1..2 -> "At least 3 characters"; else -> "3–20 letters, numbers, _ or ." }, color = if (available == true) Brand else MaterialTheme.colorScheme.onSurfaceVariant) }, trailingIcon = { if (available == true) Icon(Icons.Filled.CheckCircle, null, tint = Brand) }); Spacer(Modifier.height(14.dp))
        OutlinedTextField(about, { about = it.take(140) }, Modifier.fillMaxWidth(), label = { Text("About you") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, shape = RoundedCornerShape(16.dp), supportingText = { Text("${about.length}/140") })
        if (error != null) Text(error!!, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(24.dp))
        Button(onClick = { scope.launch { if (name.isBlank()) { error = "Please enter your name"; return@launch }; if (available != true) { error = "Choose an available username"; return@launch }; runCatching { Repo.updateProfile(name = name, about = about, username = username) }.onFailure { error = it.message ?: "Could not save" } } }, enabled = available == true && !checking, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Continue", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(26.dp))
    }
}


@Composable
private fun HomeScreen(page: String, onPage: (String) -> Unit, onOpenChat: (String) -> Unit, onProfile: () -> Unit, onProfileContact: (User) -> Unit, onOpenGroup: (String) -> Unit) {
    val context = LocalContext.current
    val chats by Repo.db.observeChats().collectAsStateWithLifecycle(emptyList())
    val calls by Repo.db.observeCalls().collectAsStateWithLifecycle(emptyList())
    val groups by Repo.db.observeGroups().collectAsStateWithLifecycle(emptyList())
    var createGroup by remember { mutableStateOf(false) }
    val conn by Realtime.state.collectAsStateWithLifecycle()
    val myProfile by Repo.me.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pendingCall by remember { mutableStateOf<Pair<User, Boolean>?>(null) }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) pendingCall?.let { (u, video) -> CallManager.startOutgoing(u.id, u.display, u.avatar, video) }
        pendingCall = null
    }
    fun callUser(u: User, video: Boolean) {
        val wanted = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (wanted.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) CallManager.startOutgoing(u.id, u.display, u.avatar, video)
        else { pendingCall = u to video; callPerms.launch(wanted) }
    }
    LaunchedEffect(Unit) { runCatching { Repo.refreshGroups() }; runCatching { Repo.refreshStatuses() } }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(280)
        searching = true
        runCatching { Repo.search(query) }.onSuccess { results = it }
        searching = false
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (page != "status") Column {
                Row(Modifier.fillMaxWidth().height(66.dp).padding(start = 21.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when(page) { "home" -> "Danav"; "status" -> "Updates"; "groups" -> "Groups"; "calls" -> "Calls"; else -> "People" }, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.45).sp, color = LocalChatColors.current.brandTitle)
                        Text(when(page) { "home" -> if (conn == Conn.ONLINE) "Your conversations" else "Reconnecting…"; "status" -> "Share a moment with your people"; "groups" -> "Better together"; "calls" -> "Stay close, wherever you are"; else -> "Find people by username" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(page != "search") IconButton(onClick = { onPage("search") }) { Icon(Icons.Filled.Search, "Find people", tint = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = onProfile) { Avatar(myProfile, 36) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.55f))
            }
        },
        floatingActionButton = {
            if (page != "status") when(page){
                "groups" -> FloatingActionButton(onClick={createGroup=true},containerColor=Brand,contentColor=Color.White,shape=RoundedCornerShape(18.dp)){Icon(Icons.Filled.GroupAdd,"Create group")}
                "calls" -> FloatingActionButton(onClick={onPage("search")},containerColor=Brand,contentColor=Color.White,shape=RoundedCornerShape(18.dp)){Icon(Icons.Filled.Call,"New call")}
                else -> FloatingActionButton(onClick={onPage("search")},containerColor=Brand,contentColor=Color.White,shape=RoundedCornerShape(18.dp)){Icon(Icons.Filled.Edit,"New message")}
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                val itemColors = NavigationBarItemDefaults.colors(selectedIconColor = Brand, selectedTextColor = Brand, indicatorColor = Brand.copy(.12f), unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
                NavigationBarItem(selected = page == "home", onClick = { onPage("home") }, icon = { Icon(Icons.Filled.ChatBubble, null) }, label = { Text("Chats") }, colors = itemColors)
                NavigationBarItem(selected = page == "status", onClick = { onPage("status") }, icon = { Icon(Icons.Filled.DonutLarge, null) }, label = { Text("Updates") }, colors = itemColors)
                NavigationBarItem(selected = page == "groups", onClick = { onPage("groups") }, icon = { Icon(Icons.Filled.Groups, null) }, label = { Text("Groups") }, colors = itemColors)
                NavigationBarItem(selected = page == "calls", onClick = { onPage("calls") }, icon = { Icon(Icons.Filled.Call, null) }, label = { Text("Calls") }, colors = itemColors)
            }
        }
    ) { pad ->
        when (page) {
            "home" -> if (chats.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeSearchBar(onClick = { onPage("search") })
                    Spacer(Modifier.weight(1f))
                    EmptyState({ Icon(Icons.Filled.Forum, null, Modifier.size(47.dp), tint = Brand) }, "Your conversations start here", "Find someone by their username and say hello.", { FilledTonalButton(onClick = { onPage("search") }) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp)); Text("Find people") } })
                    Spacer(Modifier.weight(1f))
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 20.dp)) {
                item(key = "chat-search") { HomeSearchBar(onClick = { onPage("search") }) }
                item { if (conn != Conn.ONLINE) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) { Text("Reconnecting securely…", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp) } }
                items(chats, key = { it.peer.id }) { c -> Column { ChatRow(c, onClick = { Repo.openChat.value = c.peer.id; onOpenChat(c.peer.id) }); HorizontalDivider(Modifier.padding(start = 84.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(.55f)) } }
            }
            "groups" -> if (groups.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState({ Icon(Icons.Filled.Groups, null, Modifier.size(47.dp), tint = Brand) }, "Bring everyone together", "Create a group chat and add people by username.", { FilledTonalButton(onClick = { createGroup = true }) { Icon(Icons.Filled.GroupAdd, null); Spacer(Modifier.width(8.dp)); Text("Create a group") } })
            } else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 20.dp)) {
                item { HomeSearchBar(onClick = { onPage("search") }) }
                items(groups, key = { it.id }) { group ->
                    Column {
                        GroupConversationRow(group, onClick = { onOpenGroup(group.id) })
                        HorizontalDivider(Modifier.padding(start = 82.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(.55f))
                    }
                }
            }
            "status" -> StatusScreen()
            "calls" -> if (calls.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState({ Icon(Icons.Filled.Call, null, Modifier.size(45.dp), tint = Brand) }, "No calls yet", "Voice and video calls with your contacts will show up here.", { FilledTonalButton(onClick = { onPage("search") }) { Icon(Icons.Filled.Call, null); Spacer(Modifier.width(8.dp)); Text("Start a call") } })
            } else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
                item { SectionLabel("Recent") }
                items(calls, key = { it.first.id }) { (c, u) -> CallRow(c, u, onClick = { onProfileContact(u) }, onCall = { callUser(u, false) }, onVideo = { callUser(u, true) }) }
            }
            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp), decorationBox = { inner -> Box { if (query.isBlank()) Text("Search by username…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                    if (query.isNotBlank()) IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp)) }
                }
                if (query.isBlank()) {
                    EmptyState({ Icon(Icons.Filled.AlternateEmail, null, Modifier.size(44.dp), tint = Brand) }, "Find your people", "Search by someone's exact username or name.")
                } else if (searching) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (results.isEmpty()) Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { Text("No one found for ‘$query’", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else LazyColumn { items(results, key = { it.id }) { u -> SearchUserRow(u, onChat = { Repo.cacheUser(u); onOpenChat(u.id) }, onCall = { callUser(u, false) }, onProfile = { Repo.cacheUser(u); onProfileContact(u) }) } }
            }
        }
    }
    if(createGroup) CreateGroupDialog(onDismiss={createGroup=false},onCreate={name,ids->createGroup=false;scope.launch{runCatching{val g=Repo.createGroup(name,ids);onOpenGroup(g.id)}.onFailure{android.widget.Toast.makeText(context,it.message,android.widget.Toast.LENGTH_LONG).show()}}})
}

@Composable
private fun HomeSearchBar(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp).height(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(11.dp))
            Text("Search conversations or people", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

@Composable
private fun GroupConversationRow(group: ChatGroup, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().animateContentSize(tween(180)).clickable(onClick = onClick).padding(horizontal = 17.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupAvatar(group, 54)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                group.lastAt?.let { Text(chatTimestamp(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (group.lastType == "image") Icons.Filled.Image else if (group.lastType == "audio") Icons.Filled.Mic else Icons.Filled.Groups, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(group.lastBody ?: "${group.memberCount} members", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.clip(CircleShape).background(Brand.copy(.10f)).padding(horizontal = 8.dp, vertical = 4.dp)) { Text("${group.memberCount}", fontSize = 10.sp, color = Brand, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ChatRow(c: ChatSummary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().animateContentSize(tween(180)).clickable(onClick = onClick).padding(horizontal = 17.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.peer, 55)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.peer.display, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chatTimestamp(c.last.createdAt), color = if (c.unread > 0) Brand else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.last.sender == Repo.meId) { VerifiedTicks(c.last.status); Spacer(Modifier.width(3.dp)) }
                Text(chatPreview(c.last), Modifier.weight(1f), color = if (c.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (c.unread > 0) FontWeight.Medium else FontWeight.Normal, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.unread > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.defaultMinSize(minWidth = 21.dp, minHeight = 21.dp).clip(CircleShape).background(Brand).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { Text(if (c.unread > 99) "99+" else c.unread.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
            }
        }
    }
}

private fun preview(m: Message): String = when(m.type) { "image" -> "Photo"; "video" -> "Video"; "audio" -> "Voice message"; "file" -> m.body ?: "Document"; "deleted" -> "Message deleted"; else -> "" }
private fun chatPreview(m: Message) = if (m.type == "text" || m.type == "deleted") m.body ?: "" else (if (m.body?.isNotBlank() == true && m.type in setOf("image", "video")) m.body else preview(m))

@Composable
private fun SearchUserRow(u: User, onChat: () -> Unit, onCall: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onChat).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(u, 50, modifier = Modifier.clickable(onClick = onProfile)); Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) { Text(u.display, fontWeight = FontWeight.SemiBold); Text("@${u.username ?: ""}", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp); if (u.about.isNotBlank()) Text(u.about, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, fontSize = 12.sp) }
        IconButton(onClick = onCall) { Icon(Icons.Filled.Call, "Call", tint = Brand) }
        IconButton(onClick = onChat) { Icon(Icons.AutoMirrored.Filled.Send, "Message", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CallRow(c: CallLog, u: User, onClick: () -> Unit, onCall: () -> Unit, onVideo: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(u, 50, modifier = Modifier.clickable(onClick = onClick)); Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(u.display, fontWeight = FontWeight.SemiBold, color = if (c.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (c.outgoing) Icons.Filled.CallMade else if (c.missed) Icons.Filled.CallMissed else Icons.Filled.CallReceived, null, Modifier.size(15.dp), tint = if (c.missed) MaterialTheme.colorScheme.error else Brand)
                Spacer(Modifier.width(5.dp)); Text("${if (c.video) "Video" else "Voice"} · ${formatDateTime(c.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onCall) { Icon(Icons.Filled.Call, "Voice call", tint = Brand) }
        IconButton(onClick = onVideo) { Icon(Icons.Filled.Videocam, "Video call", tint = Brand) }
    }
}
