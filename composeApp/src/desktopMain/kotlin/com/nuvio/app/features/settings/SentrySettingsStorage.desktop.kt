package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object SentrySettingsPlatform {
    actual val crashReportsSupported: Boolean = false
}

internal actual object SentrySettingsStorage {
    private const val enabledKey = "sentry_enabled"

    private val prefs = DesktopPreferences("sentry_settings")

    actual fun loadEnabled(): Boolean? = prefs.getBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        prefs.putBoolean(enabledKey, enabled)
    }
}
