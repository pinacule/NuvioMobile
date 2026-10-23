package com.nuvio.app.features.watchprogress

import platform.Foundation.NSUserDefaults

actual object WatchProgressStorage {
    private const val payloadKey = "watch_progress_payload"

    actual fun loadPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("${payloadKey}_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "${payloadKey}_$profileId")
    }

    actual fun loadBackupPayload(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("${payloadKey}_backup_$profileId")

    actual fun saveBackupPayload(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "${payloadKey}_backup_$profileId")
    }
}
