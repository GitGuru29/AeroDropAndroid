package com.aerodrop.ui.theme

// Space.kt — AeroDrop Android  [Phase 4: UI]
// The spacing scale.
//
// The old UI carried 77 dp literals across 28 distinct values, several of them
// off the 4dp grid (5, 7, 10, 22). Everything below is a multiple of 4 so rows
// align without pixel-nudging, and the scale is short on purpose: a long ramp
// invites arbitrary choices, which is how 28 values happened in the first place.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
class AeroSpacing(
    val xxs: Dp,
    val xs:  Dp,
    val sm:  Dp,
    val md:  Dp,
    val lg:  Dp,
    val xl:  Dp,
    val xxl: Dp,
)

/**
 * The scale itself, as constants. Usable from a default argument or a non-
 * composable helper, which is most of the call sites; the [AeroTheme.space]
 * override is for previews and tests that need to compress it.
 */
object AeroSpace {
    val xxs: Dp = 2.dp    // gap between a glyph and its own label
    val xs:  Dp = 4.dp    // inside a pill
    val sm:  Dp = 8.dp    // between siblings in a tight group
    val md:  Dp = 12.dp   // card padding, gap between a row and its divider
    val lg:  Dp = 16.dp   // gap between cards
    val xl:  Dp = 24.dp   // hero padding inside the drop zone
    val xxl: Dp = 32.dp   // outer gutter on a large screen
}

/**
 * The minimum size of anything tappable.
 *
 * The old UI put its "clear" and "Copy ID" actions on 9.sp text with 4dp of
 * padding — a target roughly a fifth of this. Naming the floor means a new
 * control cannot quietly re-introduce that.
 */
val TouchTarget: Dp = 48.dp

val DefaultAeroSpacing = AeroSpacing(
    xxs = AeroSpace.xxs,
    xs  = AeroSpace.xs,
    sm  = AeroSpace.sm,
    md  = AeroSpace.md,
    lg  = AeroSpace.lg,
    xl  = AeroSpace.xl,
    xxl = AeroSpace.xxl,
)

val LocalAeroSpacing = staticCompositionLocalOf { DefaultAeroSpacing }

/** The spacing scale, read the same way as `MaterialTheme.colorScheme`. */
object AeroTheme {
    val space: AeroSpacing
        @Composable @ReadOnlyComposable get() = LocalAeroSpacing.current

    /** Page gutter. Wider than the phone default on a tablet, where 16dp looks lost. */
    val gutter: Dp
        @Composable @ReadOnlyComposable get() =
            if (LocalConfiguration.current.screenWidthDp >= 600) {
                DefaultAeroSpacing.xxl
            } else {
                DefaultAeroSpacing.lg
            }
}
