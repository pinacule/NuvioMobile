package com.nuvio.app.core.ui

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object PosterCardStyleStorage {
    private const val payloadKey = "poster_card_style_payload"
    private val prefs = DesktopPreferences("postercardstylestorage")

    actual fun loadPayload(): String? =
        prefs.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
