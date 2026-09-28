package com.aerodrop.ui

// Format.kt — AeroDrop Android  [Phase 4: UI]
// Formatting helpers.
//
// These moved out of AeroTheme.kt when that file became a real theme: they are
// not design tokens, and only the UI layer formats bytes for display. The wire
// format and the transfer engine keep their own, byte-exact parsing.

import java.util.Locale

/** 1 048 576 → "1.0 MB". Matches the units the Mac prints in its footer. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatSpeed(mbPerSecond: Double): String =
    if (mbPerSecond <= 0.0) "—" else String.format(Locale.US, "%.1f MB/s", mbPerSecond)

fun formatPercent(fraction: Float): String =
    String.format(Locale.US, "%d%%", (fraction.coerceIn(0f, 1f) * 100).toInt())
