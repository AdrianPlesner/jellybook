package dk.azp.jellybook.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** h:mm:ss, or mm:ss below one hour. */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** "3h 12m", "45m" or "< 1m". */
fun formatDurationShort(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "< 1m"
    }
}

fun formatRelativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val elapsed = nowMs - epochMs
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        elapsed < 60_000 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days < 7 -> "$days d ago"
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}

fun formatSpeed(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else String.format(Locale.ROOT, "%.2fx", speed).replace("0x", "x")
