package com.nuvio.app.features.player

import java.awt.Desktop
import java.net.URI

private const val DesktopSystemPlayerId = "desktop_system"

/**
 * Desktop stub: the only "external player" is the OS default handler,
 * opened via [Desktop.browse].
 */
internal actual object ExternalPlayerPlatform {
    actual fun defaultPlayerId(): String? = DesktopSystemPlayerId

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        listOf(ExternalPlayerApp(DesktopSystemPlayerId, "System default"))

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        if (!Desktop.isDesktopSupported() ||
            !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            return ExternalPlayerOpenResult.NoPlayerAvailable
        }
        return runCatching {
            Desktop.getDesktop().browse(URI(request.sourceUrl))
            ExternalPlayerOpenResult.Opened
        }.getOrDefault(ExternalPlayerOpenResult.Failed)
    }

    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult = ExternalPlayerIntentResult.Success(request)
}
