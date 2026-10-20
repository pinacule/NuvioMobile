package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    /** Key of the removed DownloadsSettingsRepository (batch 21 cleanup, R-19). */
    private const val legacySettingsPayloadKey = "downloads_settings_payload"

    actual fun loadPayload(): String? {
        removeLegacySettingsPayload()
        return NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))
    }

    /**
     * One-time cleanup: [legacySettingsPayloadKey] has been unread since the
     * settings repository was removed, so drop the active profile's copy.
     * Other profiles' copies go when their payload is next loaded.
     */
    private fun removeLegacySettingsPayload() {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(legacySettingsPayloadKey)
        if (defaults.objectForKey(key) != null) {
            defaults.removeObjectForKey(key)
        }
    }

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

}
