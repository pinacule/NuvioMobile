package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"
    private val prefs = DesktopPreferences("authstorage")

    actual fun loadAnonymousUserId(): String? =
        prefs.getString(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        prefs.putString(KEY_ANONYMOUS_USER_ID, userId)
    }

    actual fun clearAnonymousUserId() {
        prefs.remove(KEY_ANONYMOUS_USER_ID)
    }
}
