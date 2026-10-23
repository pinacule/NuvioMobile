package com.nuvio.app.features.tracking

import platform.Foundation.NSUserDefaults

internal actual object TrackingScrobbleOutboxStorage {
    private const val PAYLOAD_KEY = "scrobble_outbox_payload"
    private const val CORRUPT_BACKUP_KEY = "scrobble_outbox_corrupt_backup"

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    actual fun loadPayload(): String? = defaults.stringForKey(PAYLOAD_KEY)

    actual fun savePayload(payload: String) {
        defaults.setObject(payload, forKey = PAYLOAD_KEY)
    }

    actual fun saveCorruptPayloadBackup(payload: String) {
        defaults.setObject(payload, forKey = CORRUPT_BACKUP_KEY)
    }

    actual fun removeProfile(profileId: Int) {
        defaults.setObject(
            TrackingScrobbleOutbox.filterPayloadForProfile(defaults.stringForKey(PAYLOAD_KEY), profileId),
            forKey = PAYLOAD_KEY,
        )
    }
}
