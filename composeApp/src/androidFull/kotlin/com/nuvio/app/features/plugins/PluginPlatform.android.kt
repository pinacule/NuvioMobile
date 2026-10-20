package com.nuvio.app.features.plugins

import android.content.Context
import android.content.SharedPreferences

internal object PluginStorage {
    private const val preferencesName = "nuvio_plugins"
    private const val pluginsStateKey = "plugins_state"
    private const val scraperCodeDirectoryName = "nuvio_plugin_scrapers"

    private var preferences: SharedPreferences? = null
    private var scraperCodeStore: PluginScraperCodeFileStore? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        scraperCodeStore = PluginScraperCodeFileStore(context.filesDir.resolve(scraperCodeDirectoryName))
    }

    fun loadState(profileId: Int): String? =
        preferences?.getString("${pluginsStateKey}_$profileId", null)

    fun saveState(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString("${pluginsStateKey}_$profileId", payload)
            ?.apply()
    }

    fun hasScraperCode(profileId: Int, scraperId: String): Boolean =
        scraperCodeStore?.contains(profileId, scraperId) == true

    fun loadScraperCode(profileId: Int, scraperId: String): String? =
        scraperCodeStore?.load(profileId, scraperId)

    fun saveScraperCode(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean = scraperCodeStore?.save(profileId, scraperId, code, overwrite) == true

    fun deleteScraperCode(profileId: Int, scraperId: String): Boolean =
        scraperCodeStore?.delete(profileId, scraperId) == true

    fun deleteScraperCodeForProfile(profileId: Int): Boolean =
        scraperCodeStore?.deleteProfileDir(profileId) == true

    fun loadScraperSettings(profileId: Int, scraperId: String): String? {
        val prefs = preferences ?: return null
        val newKey = scraperSettingsKey(profileId, scraperId)
        prefs.getString(newKey, null)?.let { return it }
        // One-time migration from the pre-profile key (settings were shared across profiles).
        val legacyKey = "settings_${scraperId}"
        val legacy = prefs.getString(legacyKey, null) ?: return null
        prefs.edit().putString(newKey, legacy).remove(legacyKey).apply()
        return legacy
    }

    fun saveScraperSettings(profileId: Int, scraperId: String, payload: String) {
        preferences
            ?.edit()
            ?.putString(scraperSettingsKey(profileId, scraperId), payload)
            ?.apply()
    }

    private fun scraperSettingsKey(profileId: Int, scraperId: String) =
        "settings_${profileId}_${scraperId}"
}

internal fun currentPluginPlatform(): String = "android"

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
