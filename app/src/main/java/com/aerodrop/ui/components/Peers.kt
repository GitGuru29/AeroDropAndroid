package com.aerodrop.ui.components

// Peers.kt — AeroDrop Android  [Phase 4: UI]
// Discovery results, as a single-select list.
//
// The row's selection is now exposed as `Role.RadioButton` plus a `selected`
// semantics property, so TalkBack announces "selected, 1 of 3" rather than
// "button". The ⌘ avatar became a laptop glyph: the ⌘ means "Mac" to someone
// who already knows the app and means nothing to a screen reader.

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aerodrop.R
import com.aerodrop.discovery.AeroPeer
import com.aerodrop.ui.theme.AeroColors
import com.aerodrop.ui.theme.AeroRadius
import com.aerodrop.ui.theme.AeroSpace
import com.aerodrop.ui.theme.AeroType

@Composable
fun PeerCard(
    peers: List<AeroPeer>,
    selected: AeroPeer?,
    browsing: Boolean,
    onSelect: (AeroPeer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    AeroCard(modifier = modifier) {
        Column {
            AeroSection(
                label = stringResource(R.string.section_nearby),
                trailing = {
                    AeroValue(
                        text = when {
                            peers.isNotEmpty() ->
                                pluralStringResource(R.plurals.peers_found, peers.size, peers.size)
                            browsing -> stringResource(R.string.peers_scanning)
                            else -> stringResource(R.string.peers_no_signal)
                        },
                        style = AeroType.Caption,
                        color = AeroColors.Muted,
                    )
                },
            )

            Spacer(Modifier.height(AeroSpace.sm))
            HorizontalDivider(color = AeroColors.Hairline)
            Spacer(Modifier.height(AeroSpace.sm))

            if (peers.isEmpty()) {
                PeerEmptyState(browsing = browsing)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AeroSpace.xs)) {
                    peers.forEach { peer ->
                        PeerRow(
                            peer = peer,
                            selected = peer.name == selected?.name,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(peer)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerEmptyState(browsing: Boolean) {
    val icon: ImageVector = if (browsing) Icons.Rounded.Sensors else Icons.Rounded.Wifi
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AeroSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AeroColors.SurfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                // The text below already carries the meaning; naming the icon too
                // would make TalkBack read the same sentence out twice.
                contentDescription = null,
                tint = AeroColors.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(AeroSpace.sm))
        Text(
            text = stringResource(
                if (browsing) R.string.peers_empty_browsing else R.string.peers_empty_idle
            ),
            style = AeroType.Caption,
            color = AeroColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PeerRow(peer: AeroPeer, selected: Boolean, onClick: () -> Unit) {
    AeroSelectableRow(
        selected = selected,
        onClick = onClick,
        contentDescription = stringResource(
            if (selected) R.string.peer_cd_selected else R.string.peer_cd,
            peer.name,
            peer.displayAddress,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AeroRadius.Row))
                    .background(if (selected) AeroColors.AccentBloom else AeroColors.SurfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.LaptopMac,
                    contentDescription = null,
                    tint = if (selected) AeroColors.Accent else AeroColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.width(AeroSpace.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = AeroType.Title,
                    color = AeroColors.OnSurface,
                    maxLines = 1,
                )
                AeroValue(
                    text = peer.displayAddress,
                    style = AeroType.Caption,
                    color = AeroColors.Muted,
                )
            }

            if (selected) {
                AeroBadge(text = stringResource(R.string.peers_selected))
            }
        }
    }
}
