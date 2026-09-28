package com.aerodrop.ui.theme

// Shape.kt — AeroDrop Android  [Phase 4: UI]
// Corner radii.
//
// The old UI chose 5/12/16/18/22 dp per card by eye, which is why no two cards
// had the same silhouette. There are only three shapes in this design: a card, a
// row inside a card, and a pill. Everything else is one of those.

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object AeroRadius {
    /** Outer containers: peers, drop zone, queue, footer. */
    val Card = 20.dp

    /** A card's primary child: a peer row, a transfer row, a button. */
    val Row = 12.dp

    /** Pills and badges: the progress track, the selected chip. */
    val Pill = 100.dp
}

val AeroShapes = Shapes(
    extraSmall = RoundedCornerShape(AeroRadius.Row),
    small      = RoundedCornerShape(AeroRadius.Row),
    medium     = RoundedCornerShape(AeroRadius.Card),
    large      = RoundedCornerShape(AeroRadius.Card),
    extraLarge = RoundedCornerShape(AeroRadius.Card),
)
