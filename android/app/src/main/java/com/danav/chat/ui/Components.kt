package com.danav.chat.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.danav.chat.data.MsgStatus
import com.danav.chat.data.User
import com.danav.chat.ui.theme.Brand
import com.danav.chat.ui.theme.BlueTick

@Composable
fun Avatar(user: User?, size: Int = 48, online: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val palette = listOf(Color(0xFF5477D4), Color(0xFFDB8A42), Color(0xFF9B58A4), Color(0xFF238C78), Color(0xFFCB596D))
    val color = palette[(user?.id?.hashCode()?.and(Int.MAX_VALUE) ?: 1) % palette.size]
    val onlineScale by animateFloatAsState(if (online) 1f else 0f, animationSpec = spring(stiffness = 520f), label = "online-indicator")
    Box(
        modifier.size(size.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (onClick != null) Modifier.semantics { contentDescription = user?.display ?: "Profile" } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(color.copy(.85f), color)))) {
            if (!user?.avatar.isNullOrBlank()) AsyncImage(user?.avatar, user?.display, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(user?.display?.firstOrNull()?.uppercase() ?: "D", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size * .38).sp)
            }
        }
        if (online) Box(
            Modifier.align(Alignment.BottomEnd).offset(x = (-1).dp, y = (-1).dp).size((size * .25).dp)
                .graphicsLayer(scaleX = onlineScale, scaleY = onlineScale).clip(CircleShape)
                .background(Brand).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

@Composable
fun VerifiedTicks(status: Int, modifier: Modifier = Modifier) {
    when (status) {
        MsgStatus.READ -> Icon(Icons.Filled.DoneAll, null, modifier.size(17.dp), tint = BlueTick)
        MsgStatus.DELIVERED -> Icon(Icons.Filled.DoneAll, null, modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        MsgStatus.SENT -> Icon(Icons.Filled.Check, null, modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        MsgStatus.PENDING -> CircularProgressIndicator(modifier.size(10.dp), strokeWidth = 1.dp)
        MsgStatus.FAILED -> Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
}

@Composable
fun EmptyState(icon: @Composable () -> Unit, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(108.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .16f), MaterialTheme.colorScheme.primary.copy(alpha = .05f)))), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(22.dp)); it() }
    }
}

@Composable
fun StatusPill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = CircleShape, color = color.copy(alpha = .10f)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
