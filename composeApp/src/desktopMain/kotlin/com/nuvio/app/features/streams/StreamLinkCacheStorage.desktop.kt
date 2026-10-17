package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object StreamLinkCacheStorage {
    private val prefs = DesktopPreferences("stream_link_cache")

    actual fun loadEntry(hashedKey: String): String? = prefs.getString(hashedKey)

    actual fun saveEntry(hashedKey: String, payload: String) {
        prefs.putString(hashedKey, payload)
    }

    actual fun removeEntry(hashedKey: String) {
        prefs.remove(hashedKey)
    }
}
