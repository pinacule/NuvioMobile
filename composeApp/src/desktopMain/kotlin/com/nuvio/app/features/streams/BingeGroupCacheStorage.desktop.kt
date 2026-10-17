package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object BingeGroupCacheStorage {
    private val prefs = DesktopPreferences("binge_group_cache")

    actual fun load(hashedKey: String): String? = prefs.getString(hashedKey)

    actual fun save(hashedKey: String, value: String) {
        prefs.putString(hashedKey, value)
    }

    actual fun remove(hashedKey: String) {
        prefs.remove(hashedKey)
    }
}
