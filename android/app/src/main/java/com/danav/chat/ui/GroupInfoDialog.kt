package com.danav.chat.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danav.chat.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GroupInfoDialog(groupId:String,onDismiss:()->Unit){
    val groups by Repo.db.observeGroups().collectAsStateWithLifecycle(emptyList())
    val group=groups.firstOrNull{it.id==groupId}
    var members by remember{mutableStateOf<List<User>>(emptyList())}
    var query by remember{mutableStateOf("")};var results by remember{mutableStateOf<List<User>>(emptyList())};var addMode by remember{mutableStateOf(false)};var name by remember(group?.name){mutableStateOf(group?.name?:"")}
    val scope= rememberCoroutineScope()
    LaunchedEffect(groupId){runCatching{members=Repo.groupMembers(groupId)}}
    LaunchedEffect(query){if(query.isBlank()){results=emptyList();return@LaunchedEffect};delay(250);runCatching{Repo.search(query)}.onSuccess{results=it.filter{u->members.none{m->m.id==u.id}}}}
    AlertDialog(onDismissRequest=onDismiss,title={Text(group?.name?:"Group info")},text={Column{
        Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(62.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Icon(Icons.Filled.Groups,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(30.dp))};Spacer(Modifier.width(12.dp));Column{Text("${members.size} members",fontWeight=FontWeight.SemiBold);Text(if(group?.createdBy==Repo.meId)"You are an admin" else "Group conversation",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        if(group?.createdBy==Repo.meId){Spacer(Modifier.height(12.dp));OutlinedTextField(name,{name=it.take(60)},Modifier.fillMaxWidth(),label={Text("Group name")},singleLine=true,shape=RoundedCornerShape(12.dp));TextButton(onClick={scope.launch{runCatching{Api.put("/groups/$groupId/rename",org.json.JSONObject().put("name",name));Repo.refreshGroups()};addMode=false}}){Text("Save group name")}}
        Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){Text("Participants",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);if(group?.createdBy==Repo.meId)TextButton(onClick={addMode=!addMode}){Icon(Icons.Filled.PersonAdd,null);Text("Add")}}
        if(addMode){OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search username")},singleLine=true,shape=RoundedCornerShape(12.dp));Column(Modifier.heightIn(max=130.dp).verticalScroll(rememberScrollState())){results.forEach{u->TextButton(onClick={scope.launch{runCatching{Repo.addGroupMembers(groupId,listOf(u.id));members=Repo.groupMembers(groupId);query="";results=emptyList()}}}){Text("+  ${u.display} (@${u.username})")}}}}
        LazyColumn(Modifier.heightIn(max=250.dp)){items(members,key={it.id}){u->Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Avatar(u,38);Spacer(Modifier.width(10.dp));Text(u.display,Modifier.weight(1f));if(u.id==group?.createdBy)Text("Admin",fontSize=11.sp,color=MaterialTheme.colorScheme.primary)}}}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
}
