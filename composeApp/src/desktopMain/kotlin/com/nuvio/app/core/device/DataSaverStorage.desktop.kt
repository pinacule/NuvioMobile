package com.nuvio.app.core.device

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DataSaverStorage {
    private const val payloadKey = "data_saver_payload"

    private val prefs = DesktopPreferences("data_saver")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
