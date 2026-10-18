package com.nuvio.app.features.tracking

import platform.Foundation.NSUserDefaults

internal actual object TrackingScrobbleOutboxStorage {
    private const val PAYLOAD_KEY = "scrobble_outbox_payload"

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    actual fun loadPayload(): String? = defaults.stringForKey(PAYLOAD_KEY)

    actual fun savePayload(payload: String) {
        defaults.setObject(payload, forKey = PAYLOAD_KEY)
    }

    actual fun removeProfile(profileId: Int) {
        defaults.setObject(
            TrackingScrobbleOutbox.filterPayloadForProfile(defaults.stringForKey(PAYLOAD_KEY), profileId),
            forKey = PAYLOAD_KEY,
        )
    }
}
