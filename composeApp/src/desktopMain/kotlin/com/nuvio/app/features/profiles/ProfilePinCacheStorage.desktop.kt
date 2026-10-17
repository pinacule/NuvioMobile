package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object ProfilePinCacheStorage {
    private val prefs = DesktopPreferences("profile_pin_cache")

    actual fun loadPayload(profileIndex: Int): String? =
        prefs.getString(payloadKey(profileIndex))

    actual fun savePayload(profileIndex: Int, payload: String) {
        prefs.putString(payloadKey(profileIndex), payload)
    }

    actual fun removePayload(profileIndex: Int) {
        prefs.remove(payloadKey(profileIndex))
    }

    private fun payloadKey(profileIndex: Int): String = "profile_pin_cache_$profileIndex"
}
