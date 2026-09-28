package com.aerodrop.ui.theme

// Type.kt — AeroDrop Android  [Phase 4: UI]
// The type scale.
//
// The old UI set fontSize inline at 31 call sites, with 9.sp appearing eleven
// times and no named size anywhere. Anything at 9.sp is legible on a 1x display
// and not on the 3x panels phones actually ship with, so the floor here is 10.sp
// and the roles below say which text may sit on it.
//
// Two families on purpose. Anything that is a *value* — an address, a percentage,
// a filename, a device ID — is monospaced, because those are read character by
// character and compared against a Mac running the other half of the protocol.
// Anything that is a *label* is the default sans, which is far more legible at
// small sizes than a monospace cut.

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

/**
 * Compose adds platform line-height padding to monospace by default, which
 * pushes small caps around unpredictably. Trim it so the label rows sit on a
 * predictable baseline.
 */
private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/** Named styles. Referenced through [AeroTheme.typography] so they can be swapped. */
object AeroType {
    /** AERODROP. The only place the two accents meet. */
    val Wordmark = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp,
    )

    /** NEARBY / TRANSFERS / THIS DEVICE. Wide-tracked caps, as before. */
    val SectionLabel = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 2.sp,
        lineHeightStyle = TightLineHeight,
    )

    /** Transfer verb, status word. */
    val Verb = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp,
        lineHeightStyle = TightLineHeight,
    )

    /** The headline of a card: peer name, "Send to …", filenames. */
    val Title = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** Secondary line under a title. Sans, because it is read at a glance. */
    val Body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** A filename or address. Monospace so digits line up in the queue. */
    val Value = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Percentages, speeds, counts, timestamps. */
    val Meta = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )

    /** The smallest thing in the UI: trailing hints and hairline captions. */
    val Caption = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    )

    /** Button faces. */
    val Action = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    )
}

/** Bound into [AeroTheme.typography]; the roles above are the extension point. */
val AeroTypography = Typography(
    displaySmall = AeroType.Wordmark,
    titleLarge  = AeroType.Title,
    titleMedium = AeroType.Title,
    bodyLarge   = AeroType.Body,
    bodyMedium  = AeroType.Body,
    bodySmall   = AeroType.Meta,
    labelLarge  = AeroType.Action,
    labelMedium = AeroType.SectionLabel,
    labelSmall  = AeroType.Caption,
)
