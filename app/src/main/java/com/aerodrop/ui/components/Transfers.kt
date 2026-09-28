package com.aerodrop.ui.components

// Transfers.kt — AeroDrop Android  [Phase 4: UI]
// What is moving, and what has moved.
//
// Both the live rows and the finished rows go through [DirectionIcon] and
// [AeroValue], so the two directions cannot drift apart and a filename is always
// monospaced. Colour only ever carries success and failure — never direction.

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aerodrop.Direction
import com.aerodrop.R
import com.aerodrop.TransferUi
import com.aerodrop.ui.formatBytes
import com.aerodrop.ui.formatPercent
import com.aerodrop.ui.formatSpeed
import com.aerodrop.ui.theme.AeroColors
import com.aerodrop.ui.theme.AeroSpace
import com.aerodrop.ui.theme.AeroType

/** Stack of in-flight transfers. Renders nothing when both directions are idle. */
@Composable
fun LiveTransfers(
    running: List<TransferUi.Running>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AeroSpace.sm)) {
        running.forEach { LiveTransferCard(it) }
    }
}

@Composable
private fun LiveTransferCard(state: TransferUi.Running) {
    val sending = state.direction == Direction.Outgoing
    val verb = stringResource(if (sending) R.string.direction_sending else R.string.direction_receiving)
    val verbCd = stringResource(if (sending) R.string.cd_sending else R.string.cd_receiving)

    // Smooths the ~100 ms progress ticks the transfer engine emits.
    val fraction by animateFloatAsState(
        targetValue = state.fraction,
        animationSpec = tween(220),
        label = "progress",
    )
    val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()

    AeroCard(borderColor = AeroColors.AccentEdge.copy(alpha = 0.35f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionIcon(direction = state.direction, contentDescription = verbCd)
                Spacer(Modifier.width(AeroSpace.xs))
                Text(verb, style = AeroType.Verb, color = AeroColors.Accent)
                Spacer(Modifier.weight(1f))
                if (state.sizeBytes > 0L) {
                    AeroValue(
                        text = formatBytes(state.sizeBytes),
                        style = AeroType.Caption,
                        color = AeroColors.Muted,
                    )
                }
            }

            Spacer(Modifier.height(AeroSpace.sm))
            AeroValue(
                text = state.filename.ifBlank { stringResource(R.string.transfer_unnamed) },
                style = AeroType.Value,
                color = AeroColors.OnSurface,
            )

            Spacer(Modifier.height(AeroSpace.sm))
            WaveProgress(
                fraction = fraction,
                label = stringResource(R.string.transfer_progress, state.filename, percent),
            )

            Spacer(Modifier.height(AeroSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AeroValue(
                    text = formatPercent(fraction),
                    style = AeroType.Meta,
                    color = AeroColors.Accent,
                )
                Spacer(Modifier.weight(1f))
                AeroValue(
                    text = formatSpeed(state.speedMBs),
                    style = AeroType.Meta,
                    color = AeroColors.Muted,
                )
            }
        }
    }
}

/** Finished transfers, newest first, with a real 48dp "clear" action. */
@Composable
fun QueueCard(
    history: List<TransferUi.Finished>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroCard(modifier = modifier) {
        Column {
            AeroSection(
                label = stringResource(R.string.section_transfers),
                trailing = {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.action_clear_cd),
                            tint = AeroColors.Muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            Spacer(Modifier.height(AeroSpace.xs))
            history.forEach { QueueRow(it) }
        }
    }
}

@Composable
private fun QueueRow(item: TransferUi.Finished) {
    val sending = item.direction == Direction.Outgoing
    val name = item.filename.ifBlank { stringResource(R.string.transfer_unnamed) }
    val directionCd = stringResource(if (sending) R.string.cd_sending else R.string.cd_receiving)
    val stateCd = stringResource(
        if (item.success) R.string.transfer_done else R.string.transfer_failed, name
    )
    val statusColor = if (item.success) AeroColors.Success else AeroColors.Danger

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AeroSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (item.success) AeroColors.SuccessWash else AeroColors.DangerWash
                ),
            contentAlignment = Alignment.Center,
        ) {
            DirectionIcon(
                direction = item.direction,
                contentDescription = directionCd,
                size = 14.dp,
                tint = statusColor,
            )
        }

        Spacer(Modifier.width(AeroSpace.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = AeroType.Body,
                color = AeroColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.detail.isNotBlank()) {
                AeroValue(
                    text = item.detail,
                    style = AeroType.Caption,
                    color = AeroColors.Muted,
                )
            }
        }

        Spacer(Modifier.width(AeroSpace.sm))
        Icon(
            imageVector = if (item.success) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            // The colour is not the state on its own; the icon and this label are.
            contentDescription = stateCd,
            tint = statusColor,
            modifier = Modifier.size(18.dp),
        )
    }
}
