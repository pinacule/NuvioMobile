package com.nuvio.app.core.sync

import android.content.Context
import android.content.SharedPreferences

internal actual object ProfileSettingsPendingPushStorage {
    private const val preferencesName = "nuvio_profile_settings_pending_push"
    private const val payloadKey = "pending_push_generations_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey, payload)
            ?.apply()
    }

    actual fun clearPayload() {
        preferences
            ?.edit()
            ?.remove(payloadKey)
            ?.apply()
    }
}
