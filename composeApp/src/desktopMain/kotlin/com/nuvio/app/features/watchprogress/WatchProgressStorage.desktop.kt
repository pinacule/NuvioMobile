package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object WatchProgressStorage {
    private val prefs = DesktopPreferences("watch_progress")

    actual fun loadPayload(profileId: Int): String? =
        prefs.getString(payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        prefs.putString(payloadKey(profileId), payload)
    }

    private fun payloadKey(profileId: Int): String = "watch_progress_payload_$profileId"
}
