package com.nuvio.app.features.watched

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object WatchedStorage {
    private val prefs = DesktopPreferences("watched")

    actual fun loadPayload(profileId: Int): String? =
        prefs.getString(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        prefs.putString(payloadKey(profileId), payload)
    }

    private fun payloadKey(profileId: Int): String = "watched_payload_$profileId"
}
