package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

internal fun StreamsUiState.shouldShowAutoPlayLoading(
    expectedRequestToken: String,
    settings: PlayerSettingsUiState,
    manualSelection: Boolean,
): Boolean =
    if (requestToken == expectedRequestToken && autoPlayDecided) {
        showDirectAutoPlayOverlay
    } else {
        !manualSelection && StreamAutoPlayPolicy.isEffectivelyEnabled(settings)
    }

/**
 * Whether the adaptive auto-play pick (StreamDestination item 9) still owns the
 * loading screen. The pick yields to the configured auto-play flow
 * ([StreamAutoPlayPolicy.isEffectivelyEnabled]) without ever marking itself
 * done, so pending must also end when the repository has decided, loading has
 * finished, and no direct auto-play overlay remains — the settled-with-no-stream
 * state, where the sources list is the fallback. Mirrors the decided/overlay
 * precedent in [shouldShowAutoPlayLoading] above; the loading guard keeps the
 * pick's legitimate in-flight state (for non-direct flows the repository
 * publishes autoPlayDecided with no overlay while groups are still loading).
 */
internal fun StreamsUiState.isAdaptiveAutoPickPending(
    expectedRequestToken: String,
    manualSelection: Boolean,
    adaptivePickDone: Boolean,
): Boolean =
    !manualSelection &&
        !adaptivePickDone &&
        requestToken == expectedRequestToken &&
        !(autoPlayDecided && !isAnyLoading && !showDirectAutoPlayOverlay)

internal fun StreamsUiState.shouldUseLandscapeAutoPlayLoading(
    expectedRequestToken: String,
    settings: PlayerSettingsUiState,
    manualSelection: Boolean,
): Boolean =
    !manualSelection &&
        settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL &&
        requestToken == expectedRequestToken &&
        autoPlayDecided &&
        isDirectAutoPlayFlow &&
        showDirectAutoPlayOverlay

internal fun List<AddonStreamGroup>.areAutoPlaySourcesLoaded(
    source: StreamAutoPlaySource,
    installedAddonIds: Set<String>,
): Boolean = none { group ->
    group.isLoading && when (source) {
        StreamAutoPlaySource.ALL_SOURCES -> true
        StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> group.addonId in installedAddonIds
        StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> group.addonId !in installedAddonIds
    }
}
