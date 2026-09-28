package com.aerodrop.ui.theme

// Theme.kt — AeroDrop Android  [Phase 4: UI]
// The app theme.
//
// MainActivity used to wrap the UI in a stock darkColorScheme() while every
// colour on screen came from a hand-rolled object two files away, so Material's
// components rendered stock purple and nothing agreed with anything. This
// binds the palette, the type scale and the shapes into one MaterialTheme, and
// carries the spacing scale beside it.
//
// Dark only, deliberately. The Mac build has no light mode and the two halves
// are meant to look like the same app on a table, so there is no light scheme
// here to fall out of sync.

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun AeroDropTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAeroSpacing provides DefaultAeroSpacing) {
        MaterialTheme(
            colorScheme   = AeroColorScheme,
            typography    = AeroTypography,
            shapes        = AeroShapes,
            content       = content,
        )
    }
}
