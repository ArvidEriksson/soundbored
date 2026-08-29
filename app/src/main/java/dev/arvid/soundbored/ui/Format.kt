package dev.arvid.soundbored.ui

import java.util.Locale

/** m:ss.t — tenths matter when you are trimming the head off a word. */
fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val minutes = safe / 60_000
    val seconds = (safe % 60_000) / 1000
    val tenths = (safe % 1000) / 100
    return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
}

fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return if (safe < 60_000) {
        String.format(Locale.US, "%.1fs", safe / 1000f)
    } else {
        String.format(Locale.US, "%d:%02d", safe / 60_000, (safe % 60_000) / 1000)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}
