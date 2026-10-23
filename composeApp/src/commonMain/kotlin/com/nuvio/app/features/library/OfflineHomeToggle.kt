package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.OfflineHomeRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_home_toggle_label
import org.jetbrains.compose.resources.stringResource

/**
 * Toggle for the offline homepage, sitting at the right end of the Library
 * filter row (next to the "All types" / sort chips). Reflects the *effective*
 * offline-home mode — including when connectivity turned it on automatically —
 * and flips the session override: this is how the user enters the mode
 * manually on Wi-Fi and clicks out of it on mobile data. Purely a mode
 * toggle — it never navigates: the Library filters in place while the mode
 * is active, and the Home tab shows the downloads-only homepage.
 */
@Composable
internal fun OfflineHomeToggle(
    modifier: Modifier = Modifier,
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
            .toggleable(
                value = isActive,
                role = Role.Switch,
            ) {
                // The repository owns the effective state (connectivity can
                // flip it too), so ignore the requested value and toggle.
                OfflineHomeRepository.toggle()
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
            text = stringResource(Res.string.offline_home_toggle_label),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}
