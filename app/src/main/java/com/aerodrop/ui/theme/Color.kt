package com.aerodrop.ui.theme

// Color.kt — AeroDrop Android  [Phase 4: UI]
// The palette, as named roles rather than raw literals.
//
// Two rules this file exists to enforce:
//
// 1. One accent for both directions. Send and receive differ only by an arrow
//    and a verb, matching TransferStyle.swift on macOS, so the two directions can
//    never drift into looking like different apps. Accent is not a direction.
// 2. No ad-hoc alphas. Every tint the UI needs is a named role, so "selected"
//    means one colour instead of whoever typed copy(alpha = 0.10f) first.

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Brand roles. Prefer these over [AeroPalette] raw values. */
object AeroColors {
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

    /** Ink for text and icons sitting on a filled [Accent] surface. */
    val OnAccent = Color(0xFF04161E)

    /** Named tints. The old code spelled these as copy(alpha = …) at 30 call sites. */
    val AccentWash    = Color(0x1A22D3EE)   // 10% — resting selection
    val AccentEdge    = Color(0x8C22D3EE)   // 55% — selected outline
    val AccentBloom   = Color(0x4022D3EE)   // 25% — selected avatar
    val SuccessWash   = Color(0x2634D399)
    val DangerWash    = Color(0x26FB7185)
    val RippleIdle    = Color(0x4D22D3EE)   // 30% — outermost idle ring
    val RippleActive  = Color(0x8C22D3EE)   // 55% — outermost busy ring
    val SuccessText   = Color(0xB334D399)   // 70% — secondary success copy
}

/**
 * The same palette expressed as a Material 3 scheme, so the built-in components
 * the app does use pick up AeroDrop colours instead of stock purple. The app is
 * dark-only by design — it mirrors the macOS build, which has no light mode.
 *
 * The launcher icon duplicates these hexes in res/drawable/ic_aerodrop.xml,
 * because a vector drawable cannot read a Kotlin constant. If the accent ever
 * changes, that file is the other place to change it.
 */
val AeroColorScheme = darkColorScheme(
    primary          = AeroColors.Accent,
    onPrimary        = AeroColors.OnAccent,
    primaryContainer = AeroColors.AccentWash,
    onPrimaryContainer = AeroColors.Accent,
    secondary        = AeroColors.Secondary,
    onSecondary      = AeroColors.OnAccent,
    tertiary         = AeroColors.AccentDeep,
    onTertiary       = AeroColors.OnSurface,
    background       = AeroColors.Background,
    onBackground     = AeroColors.OnSurface,
    surface          = AeroColors.Surface,
    onSurface        = AeroColors.OnSurface,
    surfaceVariant   = AeroColors.SurfaceAlt,
    onSurfaceVariant = AeroColors.Muted,
    surfaceContainer = AeroColors.Surface,
    surfaceContainerHigh   = AeroColors.SurfaceAlt,
    surfaceContainerHighest = AeroColors.SurfaceAlt,
    surfaceContainerLow    = AeroColors.Surface,
    outline          = AeroColors.Hairline,
    outlineVariant   = AeroColors.Hairline,
    error            = AeroColors.Danger,
    onError          = AeroColors.OnAccent,
    scrim            = Color(0xCC03030A),
)
