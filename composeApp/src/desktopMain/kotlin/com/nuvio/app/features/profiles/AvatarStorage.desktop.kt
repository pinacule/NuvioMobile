package com.nuvio.app.features.profiles

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object AvatarStorage {
    private const val payloadKey = "avatar_catalog_payload"

    private val prefs = DesktopPreferences("avatar_storage")

    actual fun loadPayload(): String? = prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }
}
