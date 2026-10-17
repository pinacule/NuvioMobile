package com.nuvio.app.features.search

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object SearchHistoryStorage {
    private const val payloadKey = "search_history_payload"
    private const val enabledKey = "search_history_enabled"

    private val prefs = DesktopPreferences("search_history")

    actual fun loadPayload(): String? = prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }

    actual fun loadEnabled(): Boolean? = prefs.getBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        prefs.putBoolean(enabledKey, enabled)
    }
}
