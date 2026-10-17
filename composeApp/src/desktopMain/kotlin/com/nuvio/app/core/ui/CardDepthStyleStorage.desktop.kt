package com.nuvio.app.core.ui

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CardDepthStyleStorage {
    private const val payloadKey = "card_depth_style_payload"
    private val prefs = DesktopPreferences("carddepthstylestorage")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
