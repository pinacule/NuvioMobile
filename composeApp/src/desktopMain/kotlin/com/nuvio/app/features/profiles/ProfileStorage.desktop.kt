package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object ProfileStorage {
    private const val payloadKey = "profiles_payload"

    private val prefs = DesktopPreferences("profile_storage")

    actual fun loadPayload(): String? = prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }
}
