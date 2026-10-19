package com.nuvio.app.core.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Data saver mode: automatic (on mobile data), forced on, or forced off. */
enum class DataSaverMode {
    AUTOMATIC,
    ALWAYS_ON,
    ALWAYS_OFF,
}

data class DataSaverUiState(
    val mode: DataSaverMode = DataSaverMode.AUTOMATIC,
)

/**
 * Data saver state. Active when the mode is [DataSaverMode.ALWAYS_ON], or when the
 * mode is [DataSaverMode.AUTOMATIC] and the active network is cellular.
 *
 * While active: playback/adaptive quality is capped at 480p, the player uses a
 * smaller buffer, next-episode autoplay is disabled and background fetching
 * (preloading, prefetch) stays off.
 */
object DataSaverRepository {
    private val _uiState = MutableStateFlow(DataSaverUiState())
    val uiState: StateFlow<DataSaverUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _uiState.value = DataSaverUiState(
            mode = runCatching { DataSaverStorage.loadPayload()?.let(DataSaverMode::valueOf) }
                .getOrNull() ?: DataSaverMode.AUTOMATIC,
        )
    }

    fun setMode(mode: DataSaverMode) {
        ensureLoaded()
        if (_uiState.value.mode == mode) return
        _uiState.value = _uiState.value.copy(mode = mode)
        DataSaverStorage.savePayload(mode.name)
    }

    /** Snapshot check, safe to call from any thread. */
    fun isActiveNow(): Boolean =
        when (_uiState.value.mode) {
            DataSaverMode.ALWAYS_ON -> true
            DataSaverMode.ALWAYS_OFF -> false
            DataSaverMode.AUTOMATIC -> DeviceEnvironment.isCellular()
        }
}
