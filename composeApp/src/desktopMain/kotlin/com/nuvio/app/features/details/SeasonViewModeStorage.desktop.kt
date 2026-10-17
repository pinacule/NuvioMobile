package com.nuvio.app.features.details

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SeasonViewModeStorage {
    private const val key = "season_view_mode"

    private val prefs = DesktopPreferences("season_view_mode")

    actual fun load(): SeasonViewMode? =
        prefs.getString(ProfileScopedKey.of(key))?.let(SeasonViewMode::parse)

    actual fun save(mode: SeasonViewMode) {
        prefs.putString(ProfileScopedKey.of(key), SeasonViewMode.persist(mode))
    }
}
