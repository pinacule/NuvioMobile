package com.nuvio.app.features.tracking

import android.content.Context
import android.content.SharedPreferences

internal actual object TrackingScrobbleOutboxStorage {
    private const val PREFERENCES_NAME = "nuvio_scrobble_outbox"
    private const val PAYLOAD_KEY = "scrobble_outbox_payload"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? = preferences?.getString(PAYLOAD_KEY, null)

    actual fun savePayload(payload: String) {
        preferences?.edit()?.putString(PAYLOAD_KEY, payload)?.apply()
    }

    actual fun removeProfile(profileId: Int) {
        val prefs = preferences ?: return
        prefs.edit()
            ?.putString(PAYLOAD_KEY, TrackingScrobbleOutbox.filterPayloadForProfile(prefs.getString(PAYLOAD_KEY, null), profileId))
            ?.apply()
    }
}
