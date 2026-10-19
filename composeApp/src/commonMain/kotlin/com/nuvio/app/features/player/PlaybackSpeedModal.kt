package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_speed
import nuvio.composeapp.generated.resources.player_speed_default_badge
import nuvio.composeapp.generated.resources.player_speed_make_default
import org.jetbrains.compose.resources.stringResource

/**
 * Playback speed panel: a compact 1x–4x slider and a button to keep the speed as default.
 *
 * When [anchorBounds] (the speed button's bounds in root coordinates) is known, the popup
 * floats directly above the button, horizontally centered on it; otherwise it falls back to
 * the bottom-center position.
 */
@Composable
fun PlaybackSpeedModal(
    visible: Boolean,
    speed: Float,
    defaultSpeed: Float,
    onSpeedChanged: (Float) -> Unit,
    onMakeDefault: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchorBounds: Rect? = null,
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        // No dim: the video stays fully bright behind the compact panel.
        dimBackground = false,
    ) {
        val tokens = MaterialTheme.nuvio
        val density = LocalDensity.current
        // The slider moves freely while dragging and snaps to 0.05x steps when applied.
        var sliderValue by remember(visible) { mutableFloatStateOf(roundPlaybackSpeed(speed)) }
        val shownSpeed = roundPlaybackSpeed(sliderValue)

        // Root origin of this container, so the anchor (reported in root coordinates)
        // can be converted into container-local offsets.
        var containerOriginPx by remember { mutableStateOf(Offset.Zero) }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerOriginPx = it.boundsInRoot().topLeft },
        ) {
            val popupWidth = minOf(maxWidth - 32.dp, 208.dp)
            // (x offset from container start, lift above container bottom) for the anchored
            // position, or null when there is no anchor (fall back to bottom-center).
            val anchorPlacement = remember(anchorBounds, containerOriginPx, maxWidth, maxHeight) {
                anchorBounds?.let { bounds ->
                    with(density) {
                        val relativeTop = (bounds.top - containerOriginPx.y).toDp()
                        val relativeCenterX = (bounds.center.x - containerOriginPx.x).toDp()
                        val x = (relativeCenterX - popupWidth / 2)
                            .coerceIn(8.dp, (maxWidth - popupWidth - 8.dp).coerceAtLeast(8.dp))
                        // With BottomStart alignment, lifting by (container height - anchor top + gap)
                        // puts the popup's bottom edge 8dp above the button's top edge.
                        val lift = (maxHeight - relativeTop + 8.dp).coerceAtLeast(0.dp)
                        x to lift
                    }
                }
            }

            Column(
                modifier = Modifier
                    .width(popupWidth)
                    .then(
                        if (anchorPlacement != null) {
                            Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = anchorPlacement.first, y = -anchorPlacement.second)
                        } else {
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 96.dp)
                        },
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.92f))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.compose_player_speed),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatPlaybackSpeedLabel(shownSpeed),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (shownSpeed == defaultSpeed) {
                        Text(
                            text = stringResource(Res.string.player_speed_default_badge),
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onSpeedChanged(roundPlaybackSpeed(sliderValue)) },
                    valueRange = MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED,
                    colors = SliderDefaults.colors(
                        thumbColor = tokens.colors.accent,
                        activeTrackColor = tokens.colors.accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shownSpeed != defaultSpeed) {
                    SpeedChip(
                        label = stringResource(Res.string.player_speed_make_default),
                        selected = false,
                        onClick = { onMakeDefault(shownSpeed) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = label,
        color = if (selected) tokens.colors.onAccent else Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
