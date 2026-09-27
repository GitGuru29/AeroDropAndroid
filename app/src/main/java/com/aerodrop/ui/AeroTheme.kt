package com.aerodrop.ui

// AeroTheme.kt — AeroDrop Android  [Phase 4: UI]
// Colour tokens and formatting helpers.
//
// One accent for both directions. Send and receive differ only by an arrow and
// a verb, matching TransferStyle.swift on macOS, so the two directions can never
// drift into looking like different apps.

import androidx.compose.ui.graphics.Color
import java.util.Locale

object AeroTheme {
    val Background = Color(0xFF07070E)
    val Surface    = Color(0xFF111823)
    val SurfaceAlt = Color(0xFF161F2C)
    val Accent     = Color(0xFF22D3EE)
    val AccentDeep = Color(0xFF0891B2)
    val Secondary  = Color(0xFFE879F9)
    val Success    = Color(0xFF34D399)
    val Danger     = Color(0xFFFB7185)
    val OnSurface  = Color(0xFFE6EDF5)
    val Muted      = Color(0xFF7C8DA4)
    val Hairline   = Color(0x14FFFFFF)
}

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
