package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.OfflineHomeRepository

/**
 * Toggle for the offline homepage, sitting at the right end of the Library
 * filter row (next to the "All types" / sort chips). Reflects the *effective*
 * offline-home mode — including when connectivity turned it on automatically —
 * and flips the session override: this is how the user enters the mode
 * manually on Wi-Fi and clicks out of it on mobile data. [onToggled] runs
 * after the flip so the host can navigate to the Home tab, where the new
 * mode is what the user sees.
 */
@Composable
internal fun OfflineHomeToggle(
    modifier: Modifier = Modifier,
    onToggled: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        OfflineHomeRepository.ensureStarted()
    }
    val tokens = MaterialTheme.nuvio
    val offlineHomeState by OfflineHomeRepository.uiState.collectAsStateWithLifecycle()
    val isActive = offlineHomeState.isActive

    val background = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        tokens.colors.surface
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        tokens.colors.textPrimary
    }

    Row(
        modifier = modifier
            .clip(tokens.shapes.compactCard)
            .background(background)
            .clickable {
                OfflineHomeRepository.toggle()
                onToggled?.invoke()
            }
            .padding(horizontal = NuvioTokens.Space.s12, vertical = tokens.components.chipVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.DownloadForOffline,
            contentDescription = null,
            modifier = Modifier.size(NuvioTokens.Icon.sm + NuvioTokens.Space.s2),
            tint = contentColor,
        )
        Text(
            text = "Offline",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}
