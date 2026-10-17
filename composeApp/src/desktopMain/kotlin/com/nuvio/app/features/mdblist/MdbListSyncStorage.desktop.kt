package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object PlatformMdbListSyncStorage : MdbListSyncStorage {
    private val prefs = DesktopPreferences("mdblist_sync")

    actual override suspend fun load(profileId: Int): String? =
        prefs.getString("profile.$profileId")

    actual override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        prefs.putString("profile.$profileId", payload)
    }

    actual override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        prefs.remove("profile.$profileId")
    }

    actual fun clearAll() {
        prefs.clear()
    }
}
