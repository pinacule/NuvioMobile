package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object TraktAuthStorage {
    private val prefs = DesktopPreferences("trakt_auth")

    actual fun loadPayload(profileId: Int): String? =
        prefs.getString(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        prefs.putString(payloadKey(profileId), payload)
    }

    actual fun removeProfile(profileId: Int) {
        prefs.remove(payloadKey(profileId))
    }

    private fun payloadKey(profileId: Int): String = "trakt_auth_payload_$profileId"
}
