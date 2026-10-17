package com.nuvio.app.features.collection

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CollectionMobileSettingsStorage {
    private const val payloadKey = "collection_mobile_settings_payload"

    private val prefs = DesktopPreferences("collection_mobile_settings")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
