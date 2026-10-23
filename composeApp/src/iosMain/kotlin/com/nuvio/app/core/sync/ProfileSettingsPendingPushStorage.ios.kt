package com.nuvio.app.core.sync

import platform.Foundation.NSUserDefaults

internal actual object ProfileSettingsPendingPushStorage {
    private const val payloadKey = "profile_settings_pending_push_generations_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }

    actual fun clearPayload() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(payloadKey)
    }
}
