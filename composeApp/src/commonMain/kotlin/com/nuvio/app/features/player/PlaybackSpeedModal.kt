package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_speed
import nuvio.composeapp.generated.resources.player_speed_default_badge
import nuvio.composeapp.generated.resources.player_speed_make_default
import org.jetbrains.compose.resources.stringResource

/** Playback speed panel: a 1x–4x slider, one-tap presets and a button to keep the speed as default. */
@Composable
fun PlaybackSpeedModal(
    visible: Boolean,
    speed: Float,
    defaultSpeed: Float,
    onSpeedChanged: (Float) -> Unit,
    onMakeDefault: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        val tokens = MaterialTheme.nuvio
        // The slider moves freely while dragging and snaps to 0.05x steps when applied.
        var sliderValue by remember(visible) { mutableFloatStateOf(roundPlaybackSpeed(speed)) }
        val shownSpeed = roundPlaybackSpeed(sliderValue)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(minOf(maxWidth, 444.dp))
                    .align(Alignment.BottomStart),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.compose_player_speed),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatPlaybackSpeedLabel(shownSpeed),
                        color = Color.White,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (shownSpeed == defaultSpeed) {
                        Text(
                            text = stringResource(Res.string.player_speed_default_badge),
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 12.dp),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PLAYBACK_SPEED_PRESETS.forEach { preset ->
                        SpeedChip(
                            label = formatPlaybackSpeedLabel(preset),
                            selected = preset == shownSpeed,
                            onClick = {
                                sliderValue = preset
                                onSpeedChanged(preset)
                            },
                        )
                    }
                }
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
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
