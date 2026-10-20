package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadsSettingsUiState(
    val autoDeleteOlderEpisodes: Boolean = true,
)

/**
 * Downloads settings, persisted per profile through [DownloadsStorage].
 * Auto-delete older episodes defaults to ON: when playback of a downloaded
 * episode starts, downloaded episodes more than one behind it are removed
 * (see [episodesToAutoCleanup]).
 */
object DownloadsSettingsRepository {
    private val _uiState = MutableStateFlow(DownloadsSettingsUiState())
    val uiState: StateFlow<DownloadsSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var autoDeleteOlderEpisodes = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        autoDeleteOlderEpisodes = true
        publish()
    }

    fun setAutoDeleteOlderEpisodes(enabled: Boolean) {
        ensureLoaded()
        if (autoDeleteOlderEpisodes == enabled) return
        autoDeleteOlderEpisodes = enabled
        DownloadsStorage.saveSettingsPayload(enabled.toString())
        publish()
    }

    /** Snapshot check, safe to call off the UI thread. */
    fun isAutoDeleteOlderEpisodesEnabled(): Boolean {
        ensureLoaded()
        return autoDeleteOlderEpisodes
    }

    private fun loadFromDisk() {
        hasLoaded = true
        autoDeleteOlderEpisodes = DownloadsStorage.loadSettingsPayload()
            ?.trim()
            ?.toBooleanStrictOrNull()
            ?: true
        publish()
    }

    private fun publish() {
        _uiState.value = DownloadsSettingsUiState(
            autoDeleteOlderEpisodes = autoDeleteOlderEpisodes,
        )
    }
}
