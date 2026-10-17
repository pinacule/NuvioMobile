package com.nuvio.app.features.home

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object HomeCatalogSettingsStorage {
    private const val payloadKey = "catalog_settings_payload"

    private val prefs = DesktopPreferences("home_catalog_settings")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
