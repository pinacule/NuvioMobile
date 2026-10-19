package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_options_quality
import org.jetbrains.compose.resources.stringResource

/**
 * Stream quality switcher: a subtle top-anchored dropdown menu listing the
 * qualities actually available for this episode. The active quality is marked
 * with an accent-tinted row; tapping a row switches the stream at the current
 * position.
 */
@Composable
fun PlayerQualityModal(
    visible: Boolean,
    currentQuality: String?,
    qualities: List<String>,
    isLoading: Boolean,
    onQualitySelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        // No dim: the video stays fully bright behind the dropdown.
        dimBackground = false,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 76.dp),
    ) {
        val tokens = MaterialTheme.nuvio
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(minOf(maxWidth - 32.dp, 232.dp))
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.92f))
                    .padding(8.dp),
            ) {
                // Header: label left, current quality + collapse chevron right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_options_quality),
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = currentQuality ?: "—",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandLess,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(18.dp),
                    )
                }
                if (isLoading && qualities.isEmpty()) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f),
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 12.dp)
                            .size(22.dp),
                    )
                } else {
                    qualities.forEach { quality ->
                        val active = quality == currentQuality
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) tokens.colors.accent.copy(alpha = 0.18f)
                                    else Color.Transparent,
                                )
                                .clickable { onQualitySelected(quality) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = quality,
                                color = if (active) tokens.colors.accent else Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
