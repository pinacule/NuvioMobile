package com.nuvio.app.features.tracking

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object TrackingScrobbleOutboxStorage {
    private const val PAYLOAD_KEY = "scrobble_outbox_payload"

    private val preferences = DesktopPreferences("scrobble_outbox")

    actual fun loadPayload(): String? = preferences.getString(PAYLOAD_KEY)

    actual fun savePayload(payload: String) {
        preferences.putString(PAYLOAD_KEY, payload)
    }

    actual fun removeProfile(profileId: Int) {
        preferences.putString(
            PAYLOAD_KEY,
            TrackingScrobbleOutbox.filterPayloadForProfile(preferences.getString(PAYLOAD_KEY), profileId),
        )
    }
}
