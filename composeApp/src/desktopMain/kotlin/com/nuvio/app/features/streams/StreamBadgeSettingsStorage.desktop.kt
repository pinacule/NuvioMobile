package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object StreamBadgeSettingsStorage {
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val showAddonLogoKey = "show_addon_logo"
    private const val streamBackgroundModeKey = "stream_background_mode"
    private const val streamBadgePlacementKey = "stream_badge_placement"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"

    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey, streamBadgePlacementKey, streamBackgroundModeKey)

    private val prefs = DesktopPreferences("stream_badge_settings")
    private val legacyDebridPrefs = DesktopPreferences("debrid_settings_legacy")

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? = loadBoolean(showFileSizeBadgesKey)

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        saveBoolean(showFileSizeBadgesKey, enabled)
    }

    actual fun loadShowAddonLogo(): Boolean? = loadBoolean(showAddonLogoKey)

    actual fun saveShowAddonLogo(enabled: Boolean) {
        saveBoolean(showAddonLogoKey, enabled)
    }

    actual fun loadStreamBadgePlacement(): String? = loadString(streamBadgePlacementKey)

    actual fun loadStreamBackgroundMode(): String? = loadString(streamBackgroundModeKey)

    actual fun saveStreamBackgroundMode(mode: String) {
        saveString(streamBackgroundModeKey, mode)
    }

    actual fun saveStreamBadgePlacement(placement: String) {
        saveString(streamBadgePlacementKey, placement)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        legacyDebridPrefs.getString(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))

    actual fun clearLegacyDebridStreamBadgeRules() {
        legacyDebridPrefs.remove(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    private fun loadString(key: String): String? =
        prefs.getString(ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        prefs.putString(ProfileScopedKey.of(key), value)
    }

    private fun loadBoolean(key: String): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(key))

    private fun saveBoolean(key: String, enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(key), enabled)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
        loadStreamBadgePlacement()?.let { put(streamBadgePlacementKey, encodeSyncString(it)) }
        loadStreamBackgroundMode()?.let { put(streamBackgroundModeKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { prefs.remove(ProfileScopedKey.of(it)) }

        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
        payload.decodeSyncString(streamBadgePlacementKey)?.let(::saveStreamBadgePlacement)
        payload.decodeSyncString(streamBackgroundModeKey)?.let(::saveStreamBackgroundMode)
    }
}
