package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object ContinueWatchingEnrichmentStorage {
    private val prefs = DesktopPreferences("continue_watching_enrichment")

    actual fun loadPayload(key: String): String? = prefs.getString(key)

    actual fun savePayload(key: String, payload: String) {
        prefs.putString(key, payload)
    }

    actual fun removePayload(key: String) {
        prefs.remove(key)
    }
}
