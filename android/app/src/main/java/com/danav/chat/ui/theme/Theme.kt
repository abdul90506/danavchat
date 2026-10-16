package com.danav.chat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Brand = Color(0xFF1DAB61)
val BrandDark = Color(0xFF21C063)
val BlueTick = Color(0xFF53BDEB)
val Unread = Color(0xFF25D366)
val Danger = Color(0xFFEA0038)

@Immutable
data class ChatColors(
    val chatBg: Color,
    val pattern: Color,
    val bubbleOut: Color,
    val bubbleIn: Color,
    val bubbleText: Color,
    val meta: Color,
    val tick: Color,
    val inputBg: Color,
    val dateChip: Color,
    val dateChipText: Color,
    val systemChip: Color,
    val replyBgOut: Color,
    val replyBgIn: Color,
    val secondaryText: Color,
    val divider: Color,
    val topBar: Color,
    val topBarContent: Color,
    val brandTitle: Color,
    val fab: Color,
    val selected: Color,
    val isDark: Boolean,
)

private val LightChat = ChatColors(
    chatBg = Color(0xFFEFEAE2), pattern = Color(0x14000000), bubbleOut = Color(0xFFD9FDD3), bubbleIn = Color.White,
    bubbleText = Color(0xFF111B21), meta = Color(0xFF667781), tick = Color(0xFF8696A0), inputBg = Color.White,
    dateChip = Color(0xFFFFFFFF), dateChipText = Color(0xFF54656F), systemChip = Color(0xFFFFEECD),
    replyBgOut = Color(0xFFD1F4CC), replyBgIn = Color(0xFFF5F6F6), secondaryText = Color(0xFF667781),
    divider = Color(0xFFE9EDEF), topBar = Color.White, topBarContent = Color(0xFF111B21), brandTitle = Brand,
    fab = Brand, selected = Color(0x331DAB61), isDark = false,
)

private val DarkChat = ChatColors(
    chatBg = Color(0xFF0B141A), pattern = Color(0x0FFFFFFF), bubbleOut = Color(0xFF075E54), bubbleIn = Color(0xFF18252C),
    bubbleText = Color(0xFFE9EDEF), meta = Color(0xFF8696A0), tick = Color(0xFF8696A0), inputBg = Color(0xFF202C33),
    dateChip = Color(0xFF182229), dateChipText = Color(0xFF8696A0), systemChip = Color(0xFF182229),
    replyBgOut = Color(0xFF0F3D2C), replyBgIn = Color(0xFF1A2329), secondaryText = Color(0xFF8696A0),
    divider = Color(0xFF222D34), topBar = Color(0xFF0B141A), topBarContent = Color(0xFFE9EDEF), brandTitle = Color(0xFFE9EDEF),
    fab = BrandDark, selected = Color(0x3321C063), isDark = true,
)

val LocalChatColors = staticCompositionLocalOf { LightChat }

private val LightScheme = lightColorScheme(
    primary = Brand, onPrimary = Color.White, primaryContainer = Color(0xFFD8FDD2), onPrimaryContainer = Color(0xFF0A3B20),
    secondary = Color(0xFF008069), background = Color.White, onBackground = Color(0xFF111B21),
    surface = Color.White, onSurface = Color(0xFF111B21), surfaceVariant = Color(0xFFF0F2F5), onSurfaceVariant = Color(0xFF667781),
    surfaceContainer = Color(0xFFF7F8FA), surfaceContainerHigh = Color(0xFFF0F2F5), surfaceContainerLow = Color.White,
    error = Danger, outline = Color(0xFFD1D7DB), outlineVariant = Color(0xFFE9EDEF),
)
private val DarkScheme = darkColorScheme(
    primary = BrandDark, onPrimary = Color(0xFF0B141A), primaryContainer = Color(0xFF103529), onPrimaryContainer = Color(0xFFD8FDD2),
    secondary = Color(0xFF00A884), background = Color(0xFF0B141A), onBackground = Color(0xFFE9EDEF),
    surface = Color(0xFF0B141A), onSurface = Color(0xFFE9EDEF), surfaceVariant = Color(0xFF202C33), onSurfaceVariant = Color(0xFF8696A0),
    surfaceContainer = Color(0xFF111B21), surfaceContainerHigh = Color(0xFF202C33), surfaceContainerLow = Color(0xFF0B141A),
    error = Color(0xFFF15C6D), outline = Color(0xFF3B4A54), outlineVariant = Color(0xFF222D34),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun DanavTheme(mode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val chat = if (dark) DarkChat else LightChat
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            w.navigationBarColor = (if (dark) Color(0xFF0B141A) else Color.White).toArgb()
        }
    }
    CompositionLocalProvider(LocalChatColors provides chat) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, content = content)
    }
}
