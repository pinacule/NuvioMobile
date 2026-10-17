package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object MdbListSettingsStorage {
    private const val enabledKey = "mdblist_enabled"
    private const val apiKey = "mdblist_api_key"
    private const val useImdbKey = "mdblist_use_imdb"
    private const val useTmdbKey = "mdblist_use_tmdb"
    private const val useTomatoesKey = "mdblist_use_tomatoes"
    private const val useMetacriticKey = "mdblist_use_metacritic"
    private const val useTraktKey = "mdblist_use_trakt"
    private const val useLetterboxdKey = "mdblist_use_letterboxd"
    private const val useAudienceKey = "mdblist_use_audience"
    private const val useMalKey = "mdblist_use_mal"
    private val syncKeys = listOf(
        enabledKey,
        apiKey,
        useImdbKey,
        useTmdbKey,
        useTomatoesKey,
        useMetacriticKey,
        useTraktKey,
        useLetterboxdKey,
        useAudienceKey,
        useMalKey,
    )

    private val prefs = DesktopPreferences("mdblist_settings")

    actual fun loadEnabled(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(enabledKey))

    actual fun saveEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(enabledKey), enabled)
    }

    actual fun loadApiKey(): String? =
        prefs.getString(ProfileScopedKey.of(apiKey))

    actual fun saveApiKey(apiKey: String) {
        prefs.putString(ProfileScopedKey.of(this.apiKey), apiKey)
    }

    actual fun loadUseImdb(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useImdbKey))

    actual fun saveUseImdb(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useImdbKey), enabled)
    }

    actual fun loadUseTmdb(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useTmdbKey))

    actual fun saveUseTmdb(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useTmdbKey), enabled)
    }

    actual fun loadUseTomatoes(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useTomatoesKey))

    actual fun saveUseTomatoes(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useTomatoesKey), enabled)
    }

    actual fun loadUseMetacritic(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useMetacriticKey))

    actual fun saveUseMetacritic(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useMetacriticKey), enabled)
    }

    actual fun loadUseTrakt(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useTraktKey))

    actual fun saveUseTrakt(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useTraktKey), enabled)
    }

    actual fun loadUseLetterboxd(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useLetterboxdKey))

    actual fun saveUseLetterboxd(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useLetterboxdKey), enabled)
    }

    actual fun loadUseAudience(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useAudienceKey))

    actual fun saveUseAudience(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useAudienceKey), enabled)
    }

    actual fun loadUseMal(): Boolean? = prefs.getBoolean(ProfileScopedKey.of(useMalKey))

    actual fun saveUseMal(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useMalKey), enabled)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadApiKey()?.let { put(apiKey, encodeSyncString(it)) }
        loadUseImdb()?.let { put(useImdbKey, encodeSyncBoolean(it)) }
        loadUseTmdb()?.let { put(useTmdbKey, encodeSyncBoolean(it)) }
        loadUseTomatoes()?.let { put(useTomatoesKey, encodeSyncBoolean(it)) }
        loadUseMetacritic()?.let { put(useMetacriticKey, encodeSyncBoolean(it)) }
        loadUseTrakt()?.let { put(useTraktKey, encodeSyncBoolean(it)) }
        loadUseLetterboxd()?.let { put(useLetterboxdKey, encodeSyncBoolean(it)) }
        loadUseAudience()?.let { put(useAudienceKey, encodeSyncBoolean(it)) }
        loadUseMal()?.let { put(useMalKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { prefs.remove(ProfileScopedKey.of(it)) }

        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(apiKey)?.let(::saveApiKey)
        payload.decodeSyncBoolean(useImdbKey)?.let(::saveUseImdb)
        payload.decodeSyncBoolean(useTmdbKey)?.let(::saveUseTmdb)
        payload.decodeSyncBoolean(useTomatoesKey)?.let(::saveUseTomatoes)
        payload.decodeSyncBoolean(useMetacriticKey)?.let(::saveUseMetacritic)
        payload.decodeSyncBoolean(useTraktKey)?.let(::saveUseTrakt)
        payload.decodeSyncBoolean(useLetterboxdKey)?.let(::saveUseLetterboxd)
        payload.decodeSyncBoolean(useAudienceKey)?.let(::saveUseAudience)
        payload.decodeSyncBoolean(useMalKey)?.let(::saveUseMal)
    }
}
