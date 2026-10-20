package com.nuvio.app.features.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop stub: plugins are disabled on desktop (see [com.nuvio.app.core.build.AppFeaturePolicy]),
 * so the repository is inert and reports an empty, disabled state.
 */
actual object PluginRepository {
    private val _uiState = MutableStateFlow(PluginsUiState(pluginsEnabled = false))
    actual val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    actual fun initialize() = Unit

    actual fun onProfileChanged(profileId: Int) = Unit

    actual fun getCurrentProfileId(): Int = 1

    actual fun clearLocalState() = Unit

    actual suspend fun pullFromServer(profileId: Int) = Unit

    actual suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult =
        AddPluginRepositoryResult.Error("Plugins are not supported on desktop")

    actual fun removeRepository(manifestUrl: String) = Unit

    actual fun refreshAll() = Unit

    actual fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean) = Unit

    actual fun toggleScraper(scraperId: String, enabled: Boolean) = Unit

    actual fun setPluginsEnabled(enabled: Boolean) = Unit

    actual fun setGroupStreamsByRepository(enabled: Boolean) = Unit

    actual fun setLocalPluginSearchPaused(paused: Boolean) = Unit

    actual fun getEnabledScrapersForType(type: String): List<PluginScraper> = emptyList()

    actual suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>> =
        Result.failure(UnsupportedOperationException("Plugins are not supported on desktop"))

    actual suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>> =
        Result.failure(UnsupportedOperationException("Plugins are not supported on desktop"))
}
