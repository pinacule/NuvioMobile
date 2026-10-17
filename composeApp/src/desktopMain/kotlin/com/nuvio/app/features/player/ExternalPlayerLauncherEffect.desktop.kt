package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.net.URI

/**
 * Desktop stub: there is no activity-result contract. The launcher opens the
 * stream URL from [ExternalPlayerIntentResult.Success] in the system browser /
 * default handler via [Desktop.browse] (fire-and-forget, no result callback).
 */
@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean {
    return remember {
        { intentResult: ExternalPlayerIntentResult.Success ->
            val request = intentResult.intent as? ExternalPlayerPlaybackRequest
            val url = request?.sourceUrl?.takeIf { it.isNotBlank() }
            if (url == null ||
                !Desktop.isDesktopSupported() ||
                !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                false
            } else {
                runCatching {
                    Desktop.getDesktop().browse(URI(url))
                    true
                }.getOrDefault(false)
            }
        }
    }
}
