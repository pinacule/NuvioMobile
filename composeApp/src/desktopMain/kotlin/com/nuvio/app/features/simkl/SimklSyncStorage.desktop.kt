package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SimklSyncStorage {
    private const val payloadKey = "simkl_sync_payload"

    private val prefs = DesktopPreferences("simkl_sync")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun removeProfile(profileId: Int) {
        prefs.remove(ProfileScopedKey.of(payloadKey, profileId))
    }
}
