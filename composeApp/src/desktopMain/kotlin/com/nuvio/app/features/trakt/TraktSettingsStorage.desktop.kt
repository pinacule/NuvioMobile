package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object TraktSettingsStorage {
    private const val payloadKey = "trakt_settings_payload"

    private val prefs = DesktopPreferences("trakt_settings")

    actual fun loadPayload(): String? = prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }

    actual fun loadPendingWatchProgressSourcePayload(profileId: Int): String? =
        prefs.getString(pendingKey(profileId))

    actual fun savePendingWatchProgressSourcePayload(profileId: Int, payload: String) {
        prefs.putString(pendingKey(profileId), payload)
    }

    actual fun clearPendingWatchProgressSourcePayload(profileId: Int) {
        prefs.remove(pendingKey(profileId))
    }

    private fun pendingKey(profileId: Int): String =
        "trakt_pending_watch_progress_source_$profileId"
}
