package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    private val prefs = DesktopPreferences("downloads")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }

}
