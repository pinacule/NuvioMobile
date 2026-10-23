package com.nuvio.app.core.storage

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        DesktopPreferences.clearAll()
    }

    actual fun removeProfile(profileId: Int) {
        DesktopPreferences.removeProfileKeys(profileId)
    }
}
