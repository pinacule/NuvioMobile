package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"
    private const val backupPayloadKey = "downloads_payload_backup"

    /** Key of the removed DownloadsSettingsRepository (batch 21 cleanup, R-19). */
    private const val legacySettingsPayloadKey = "downloads_settings_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? {
        removeLegacySettingsPayload()
        return preferences?.getString(ProfileScopedKey.of(payloadKey), null)
    }

    /**
     * One-time cleanup: [legacySettingsPayloadKey] has been unread since the
     * settings repository was removed, so drop the active profile's copy.
     * Other profiles' copies go when their payload is next loaded.
     */
    private fun removeLegacySettingsPayload() {
        val prefs = preferences ?: return
        val key = ProfileScopedKey.of(legacySettingsPayloadKey)
        if (prefs.contains(key)) {
            prefs.edit().remove(key).apply()
        }
    }

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    actual fun loadBackupPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(backupPayloadKey), null)

    actual fun saveBackupPayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(backupPayloadKey), payload)
            ?.apply()
    }

}
