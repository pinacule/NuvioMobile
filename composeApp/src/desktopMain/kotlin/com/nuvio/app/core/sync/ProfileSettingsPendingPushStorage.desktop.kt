package com.nuvio.app.core.sync

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object ProfileSettingsPendingPushStorage {
    private const val payloadKey = "pending_push_generations_payload"

    private val prefs = DesktopPreferences("profile_settings_pending_push")

    actual fun loadPayload(): String? =
        prefs.getString(payloadKey)

    actual fun savePayload(payload: String) {
        prefs.putString(payloadKey, payload)
    }

    actual fun clearPayload() {
        prefs.remove(payloadKey)
    }
}
