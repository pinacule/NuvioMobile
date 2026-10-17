package com.nuvio.app.features.library

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object LibraryStorage {
    private fun payloadKey(profileId: Int) = "library_payload_$profileId"

    private val prefs = DesktopPreferences("library")

    actual fun loadPayload(profileId: Int): String? =
        prefs.getString(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        prefs.putString(payloadKey(profileId), payload)
    }
}
