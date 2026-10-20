package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    /** Key of the removed DownloadsSettingsRepository (batch 21 cleanup, R-19). */
    private const val legacySettingsPayloadKey = "downloads_settings_payload"

    private val prefs = DesktopPreferences("downloads")

    actual fun loadPayload(): String? {
        removeLegacySettingsPayload()
        return prefs.getString(ProfileScopedKey.of(payloadKey))
    }

    /**
     * One-time cleanup: [legacySettingsPayloadKey] has been unread since the
     * settings repository was removed, so drop the active profile's copy.
     * Other profiles' copies go when their payload is next loaded.
     */
    private fun removeLegacySettingsPayload() {
        val key = ProfileScopedKey.of(legacySettingsPayloadKey)
        if (prefs.getString(key) != null) {
            prefs.remove(key)
        }
    }

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }

}
