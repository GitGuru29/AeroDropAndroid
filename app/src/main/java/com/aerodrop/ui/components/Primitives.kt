package com.aerodrop.ui.components

// Primitives.kt — AeroDrop Android  [Phase 4: UI]
// The shared vocabulary every screen is built from.
//
// These were all private to RootScreen.kt and hardcoded at the call site. They
// are here so the shape, the tint and the accessibility label of a card, a
// label, a badge or a progress bar are decided once.
//
// Two invariants from the macOS build are enforced here rather than trusted to
// each call site: direction is an arrow plus a verb and never a colour, and a
// transfer is water filling a vessel.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aerodrop.Direction
import com.aerodrop.ui.theme.AeroColors
import com.aerodrop.ui.theme.AeroRadius
import com.aerodrop.ui.theme.AeroSpace
import com.aerodrop.ui.theme.AeroType

/**
 * The app's only container. Takes a hairline it did not have before: surface and
 * background are close enough in value (#111823 on #07070E) that a borderless
 * card lost its edge on OLED panels, where true black is darker than the window
 * background we asked for.
 */
@Composable
fun AeroCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AeroSpace.md),
    containerColor: Color = AeroColors.Surface,
    borderColor: Color = AeroColors.Hairline,
    shape: Shape = RoundedCornerShape(AeroRadius.Card),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = shape,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** A section title with an optional trailing control. */
@Composable
fun AeroSection(
    label: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = AeroType.SectionLabel, color = AeroColors.Accent)
        if (trailing != null) {
            Box(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * Monospaced text for anything that is a *value* rather than a label. Centralised
 * so a value can never accidentally be set in the sans face, which is the one way
 * an address or a percentage stops lining up with the Mac.
 */
@Composable
fun AeroValue(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AeroType.Value,
    color: Color = AeroColors.OnSurface,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The one place direction becomes a glyph. Arrow plus colour, never colour alone,
 * so the two directions stay distinguishable to a colourblind user and stay
 * identical to the Mac's TransferStyle.
 */
@Composable
fun DirectionIcon(
    direction: Direction,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = AeroColors.Accent,
) {
    Icon(
        imageVector = when (direction) {
            Direction.Outgoing -> Icons.Rounded.ArrowUpward
            Direction.Incoming -> Icons.Rounded.ArrowDownward
        },
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** A small capsule for status words. Replaces 8.sp text that was not readable. */
@Composable
fun AeroBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AeroColors.Accent,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(AeroRadius.Pill))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = AeroSpace.sm, vertical = 3.dp),
        style = AeroType.Verb,
        color = color,
        maxLines = 1,
    )
}
/** The live/offline dot. Announced by the header's stateDescription, not on its own. */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (active) AeroColors.Success else AeroColors.Muted)
    )
}

/**
 * Concentric rings on the drop zone — three of them, staggered, so it reads as a
 * splash rather than a spinner. Mirrors RippleLayer on macOS.
 *
 * The ring colour is fixed to the accent: the only thing that changes between
 * sending and receiving is the arrow at the centre, which keeps direction out of
 * the palette.
 */
@Composable
fun RippleMark(
    modifier: Modifier = Modifier,
    centre: @Composable () -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Box(modifier.size(120.dp), contentAlignment = Alignment.Center) {
        for (ring in 0 until 3) {
            val t = ((phase + ring) % 3f) / 3f
            val scale = 0.42f + 0.58f * t
            val alpha = ((1f - t) * 0.30f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(100.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .border(1.dp, AeroColors.Accent.copy(alpha = alpha), CircleShape)
            )
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(AeroColors.Accent.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) { centre() }
    }
}

/**
 * A waterline rather than a filled rectangle: the filled region is a rounded
 * track and the crest is a bright edge, so a stalled transfer still reads as
 * "holding water" instead of "broken". The Mac's WaveProgressBar is the same
 * idea drawn on Canvas.
 *
 * Carries a real progress semantics node, so TalkBack announces a percentage
 * instead of an unlabelled rectangle.
 */
@Composable
fun WaveProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    trackColor: Color = AeroColors.SurfaceAlt,
    fill: Brush = Brush.horizontalGradient(listOf(AeroColors.AccentDeep, AeroColors.Accent)),
) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(AeroRadius.Pill))
            .background(trackColor)
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(f.coerceAtLeast(0.02f))
                .height(10.dp)
                .clip(RoundedCornerShape(AeroRadius.Pill))
                .background(fill)
        )
    }
}

/**
 * A selectable row. Exposed so a peer list and anything else that behaves like a
 * radio group gets the same toggle semantics — `selected` and `Role.RadioButton`
 * — rather than announcing as a generic clickable.
 */
@Composable
fun AeroSelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(AeroRadius.Row)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) AeroColors.AccentWash else Color.Transparent)
            .border(
                1.dp,
                if (selected) AeroColors.AccentEdge else AeroColors.Hairline,
                shape,
            )
            .clickable(role = Role.RadioButton) { onClick() }
            .semantics {
                this.selected = selected
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .padding(horizontal = AeroSpace.md, vertical = AeroSpace.sm)
    ) { content() }
}
