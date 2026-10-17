package com.nuvio.app.features.servers

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object ServerStorage {
    private val prefs = DesktopPreferences("server_storage")

    actual fun read(key: String): String? = prefs.getString(key)

    actual fun write(key: String, value: String?) {
        if (value == null) {
            prefs.remove(key)
        } else {
            prefs.putString(key, value)
        }
    }

    actual fun clear() {
        prefs.clear()
    }
}
