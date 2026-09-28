package com.aerodrop.ui.components

// DropZone.kt — AeroDrop Android  [Phase 4: UI]
// The hero. One target, two verbs, and the answer to "what happens if I press
// this".
//
// The arrow inside the ripple is the only thing that differs between sending and
// receiving — the rings, the colour and the copy are identical, which is what
// keeps the two directions from looking like two apps.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aerodrop.R
import com.aerodrop.discovery.AeroPeer
import com.aerodrop.ui.theme.AeroColors
import com.aerodrop.ui.theme.AeroRadius
import com.aerodrop.ui.theme.AeroSpace
import com.aerodrop.ui.theme.AeroType
import com.aerodrop.ui.theme.TouchTarget

@Composable
fun DropZone(
    selected: AeroPeer?,
    receiving: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onSendMany: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = selected != null && !busy

    AeroCard(
        modifier = modifier,
        contentPadding = PaddingValues(AeroSpace.xl),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RippleMark {
                Icon(
                    imageVector = if (receiving) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(
                        if (receiving) R.string.cd_receiving else R.string.cd_sending
                    ),
                    tint = AeroColors.Accent,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.height(AeroSpace.md))

            Text(
                text = when {
                    busy -> stringResource(R.string.drop_busy)
                    selected != null -> stringResource(R.string.drop_to_peer, selected.name)
                    else -> stringResource(R.string.drop_no_peer)
                },
                style = AeroType.Title,
                color = AeroColors.OnSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(AeroSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = AeroColors.SuccessText,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(AeroSpace.xs))
                Text(
                    text = stringResource(R.string.drop_security),
                    style = AeroType.Caption,
                    color = AeroColors.SuccessText,
                )
            }

            if (canSend) {
                Spacer(Modifier.height(AeroSpace.lg))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AeroSpace.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = onSend,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = TouchTarget),
                        shape = RoundedCornerShape(AeroRadius.Row),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AeroColors.Accent,
                            contentColor = AeroColors.OnAccent,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(AeroSpace.xs))
                        Text(
                            text = stringResource(R.string.action_send_file),
                            style = AeroType.Action,
                        )
                    }

                    FilledTonalButton(
                        onClick = onSendMany,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = TouchTarget),
                        shape = RoundedCornerShape(AeroRadius.Row),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AeroColors.SurfaceAlt,
                            contentColor = AeroColors.OnSurface,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.action_send_many),
                            style = AeroType.Action,
                        )
                    }
                }
            }
        }
    }
}
