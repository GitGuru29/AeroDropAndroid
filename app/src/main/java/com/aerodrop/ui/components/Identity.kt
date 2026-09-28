package com.aerodrop.ui.components

// Identity.kt — AeroDrop Android  [Phase 4: UI]
// The wordmark and the "this device" panel.
//
// The header used to render its status dot as a bare 7dp box with no semantics,
// and its 9.sp LISTENING/OFFLINE label was the only way to tell whether the
// receiver was up. Both now collapse into one announced state.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.aerodrop.R
import com.aerodrop.ui.theme.AeroColors
import com.aerodrop.ui.theme.AeroSpace
import com.aerodrop.ui.theme.AeroType
import com.aerodrop.ui.theme.TouchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AeroHeader(
    listening: Boolean,
    advertised: String?,
    modifier: Modifier = Modifier,
) {
    val statusLabel =
        stringResource(if (listening) R.string.status_listening else R.string.status_offline)
    val statusColor = if (listening) AeroColors.Success else AeroColors.Muted
    val headerCd = stringResource(
        if (listening) R.string.status_listening else R.string.status_offline
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = headerCd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AERO", style = AeroType.Wordmark, color = AeroColors.Accent)
        Text("DROP", style = AeroType.Wordmark, color = AeroColors.Secondary)

        Spacer(Modifier.width(AeroSpace.sm))
        StatusDot(active = listening)

        Spacer(Modifier.width(AeroSpace.xs))
        Text(
            text = statusLabel.uppercase(),
            style = AeroType.Verb,
            color = statusColor,
        )

        if (!advertised.isNullOrBlank()) {
            Spacer(Modifier.weight(1f))
            AeroValue(
                text = advertised,
                modifier = Modifier.clearAndSetSemantics { },
                style = AeroType.Caption,
                color = AeroColors.Muted,
            )
        }
    }
}

/**
 * Identity and trust surface: the certificate fingerprint, a way to copy it, and
 * where received files land. The fingerprint is grouped into 8s so a human can
 * read it back to the Mac's footer; that is the whole point of the panel.
 */
@Composable
fun DeviceCard(
    fingerprint: String,
    receivedDir: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    AeroCard(modifier = modifier) {
        Column {
            AeroSection(
                label = stringResource(R.string.section_device),
                trailing = {
                    if (fingerprint.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.device_fingerprint),
                            style = AeroType.Caption,
                            color = AeroColors.Muted,
                        )
                    }
                },
            )

            if (fingerprint.isNotBlank()) {
                Spacer(Modifier.height(AeroSpace.sm))
                HorizontalDivider(color = AeroColors.Hairline)
                Spacer(Modifier.height(AeroSpace.md))

                AeroValue(
                    // Grouped so a human can compare it with the Mac's footer.
                    text = fingerprint.chunked(8).joinToString(" "),
                    style = AeroType.Meta,
                    color = AeroColors.Muted,
                )

                Spacer(Modifier.height(AeroSpace.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        // The whole row is the target, not the glyph, and it
                        // clears the 48dp minimum the label alone never did.
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
                ) {
                    Text(
                        text = stringResource(
                            if (copied) R.string.action_copied else R.string.action_copy_id
                        ),
                        style = AeroType.Verb,
                        color = if (copied) AeroColors.Success else AeroColors.Accent,
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(fingerprint))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                copied = true
                                delay(COPIED_FLASH_MS)
                                copied = false
                            }
                        },
                        modifier = Modifier.defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = if (copied) AeroColors.Success else AeroColors.Accent,
                        )
                    }
                }
            }

            if (receivedDir.isNotBlank()) {
                Spacer(Modifier.height(AeroSpace.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = AeroColors.Muted,
                    )
                    Spacer(Modifier.width(AeroSpace.xs))
                    Text(
                        text = stringResource(R.string.received_dir, receivedDir),
                        style = AeroType.Caption,
                        color = AeroColors.Muted,
                    )
                }
            }
        }
    }
}

private const val COPIED_FLASH_MS = 1_400L
