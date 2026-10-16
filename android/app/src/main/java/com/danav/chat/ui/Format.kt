package com.danav.chat.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun timeOnly(time: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))
fun dateLabel(time: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR); val year = cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = time
    return when {
        cal.get(java.util.Calendar.YEAR) == year && cal.get(java.util.Calendar.DAY_OF_YEAR) == today -> "Today"
        cal.get(java.util.Calendar.YEAR) == year && cal.get(java.util.Calendar.DAY_OF_YEAR) == today - 1 -> "Yesterday"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(time))
    }
}
fun chatTimestamp(time: Long): String {
    val now = System.currentTimeMillis()
    return if (sameDay(time, now)) timeOnly(time) else if (dateLabel(time) == "Yesterday") "Yesterday" else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(time))
}
fun sameDay(a: Long, b: Long): Boolean {
    val x = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val y = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR) && x.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR)
}
fun formatDateTime(time: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(time))
fun formatRelative(time: Long): String {
    val d = System.currentTimeMillis() - time
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        sameDay(time, System.currentTimeMillis()) -> "${d / 3_600_000} hr ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }
}
fun formatDuration(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(sec / 60, sec % 60)
}
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
fun msgLabel(m: com.danav.chat.data.Message): String = when (m.type) {
    "image" -> "Photo"
    "video" -> "Video"
    "audio" -> "Voice message"
    "file" -> m.body ?: "Document"
    "deleted" -> "Message deleted"
    else -> m.body.orEmpty()
}
