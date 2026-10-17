package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object TraktLibraryStorage {
    private const val payloadKey = "trakt_library_payload"

    private val prefs = DesktopPreferences("trakt_library")

    actual fun loadPayload(): String? = prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }
}
