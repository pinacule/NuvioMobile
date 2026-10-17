package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object ContinueWatchingPreferencesStorage {
    private const val payloadKey = "continue_watching_preferences_payload"

    private val prefs = DesktopPreferences("continue_watching_preferences")

    actual fun loadPayload(): String? = prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }
}
