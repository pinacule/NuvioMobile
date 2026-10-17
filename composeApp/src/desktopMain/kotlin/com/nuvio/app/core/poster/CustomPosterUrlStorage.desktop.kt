package com.nuvio.app.core.poster

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CustomPosterUrlStorage {
    private const val patternKey = "custom_poster_url_pattern"
    private const val enabledScreensKey = "custom_poster_enabled_screens"
    private val prefs = DesktopPreferences("customposterurlstorage")

    actual fun loadPattern(): String? =
        prefs.getString(ProfileScopedKey.of(patternKey))

    actual fun savePattern(pattern: String?) {
        val key = ProfileScopedKey.of(patternKey)
        if (pattern.isNullOrBlank()) prefs.remove(key)
        else prefs.putString(key, pattern.trim())
    }

    actual fun loadEnabledScreens(): Set<String>? =
        prefs.getStringSet(ProfileScopedKey.of(enabledScreensKey))

    actual fun saveEnabledScreens(keys: Set<String>?) {
        val key = ProfileScopedKey.of(enabledScreensKey)
        if (keys == null) prefs.remove(key)
        else prefs.putStringSet(key, keys)
    }
}
