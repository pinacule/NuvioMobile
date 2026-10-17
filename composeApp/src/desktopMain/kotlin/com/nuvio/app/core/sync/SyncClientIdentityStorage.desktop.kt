package com.nuvio.app.core.sync

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object SyncClientIdentityStorage {
    private const val clientIdKey = "client_instance_id"
    private val prefs = DesktopPreferences("syncclientidentitystorage")

    actual fun loadClientId(): String? =
        prefs.getString(clientIdKey)

    actual fun saveClientId(clientId: String) {
        prefs.putString(clientIdKey, clientId)
    }
}
