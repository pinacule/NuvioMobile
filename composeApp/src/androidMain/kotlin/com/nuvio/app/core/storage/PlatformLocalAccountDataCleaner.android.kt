package com.nuvio.app.core.storage

import android.content.Context

internal actual object PlatformLocalAccountDataCleaner {
    private const val PLUGINS_PREFERENCES_NAME = "nuvio_plugins"
    private const val PLUGIN_SCRAPERS_DIR_NAME = "nuvio_plugin_scrapers"

    /**
     * Every SharedPreferences file the app creates (audited mechanically
     * against all `preferencesName` constants and `getSharedPreferences`
     * literals in the Android source sets, 2026-10-23). The same inventory
     * backs [removeProfile]'s per-profile key sweep, so it must stay
     * complete: a file missing here survives both sign-out and profile
     * deletion.
     *
     * Deliberately NOT wiped (device / pre-login config, no account data):
     * - "nuvio_sentry_settings": a single global crash-reporting opt-in flag.
     * - "nuvio_updater": ignored update tag + update channel.
     * - "server_configuration": the custom backend's URL + publishable
     *   (public-by-design) key, needed before sign-in to reach the account.
     */
    private val preferenceNames = listOf(
        "episode_shuffle",
        "nuvio_addons",
        "nuvio_auth",
        "nuvio_autosync_settings",
        "nuvio_avatar_cache",
        "nuvio_binge_group_cache",
        "nuvio_card_depth_style",
        "nuvio_collection_mobile_settings",
        "nuvio_collections",
        "nuvio_continue_watching_preferences",
        "nuvio_custom_poster_url",
        "nuvio_cw_enrichment",
        "nuvio_data_saver",
        "nuvio_debrid_settings",
        "nuvio_discover_selection",
        "nuvio_download_live_notifications",
        "nuvio_downloads",
        "nuvio_episode_release_notifications",
        "nuvio_episode_release_notifications_platform",
        "nuvio_home_catalog_settings",
        "nuvio_library",
        "nuvio_library_display_settings",
        "nuvio_mdblist_auth",
        "nuvio_mdblist_settings",
        "nuvio_mdblist_sync",
        "nuvio_member_access",
        "nuvio_meta_screen_settings",
        "nuvio_player_settings",
        "nuvio_player_track_preferences",
        "nuvio_plugins",
        "nuvio_poster_card_style",
        "nuvio_profile_cache",
        "nuvio_profile_pin_cache",
        "nuvio_profile_settings_pending_push",
        "nuvio_resume_prompt",
        "nuvio_scrobble_outbox",
        "nuvio_search_history",
        "nuvio_season_view_mode",
        "nuvio_servers",
        "nuvio_simkl_auth",
        "nuvio_simkl_sync",
        "nuvio_stream_badge_settings",
        "nuvio_stream_link_cache",
        "nuvio_sync_client_identity",
        "nuvio_theme_settings",
        "nuvio_tmdb_settings",
        "nuvio_trakt_auth",
        "nuvio_trakt_comments",
        "nuvio_trakt_library",
        "nuvio_trakt_settings",
        "nuvio_watch_progress",
        "nuvio_watched",
        "torrent_settings",
    )

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun wipe() {
        val context = appContext ?: return
        preferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
        context.filesDir.resolve("nuvio_plugin_scrapers").deleteRecursively()
    }

    actual fun removeProfile(profileId: Int) {
        val context = appContext ?: return
        // Every per-profile key in the storage layer carries the profile id
        // as a "_<profileId>" suffix (the ProfileScopedKey convention, also
        // used by the payload storages that take an explicit profile id), so
        // removing by suffix purges exactly the deleted profile's keys in
        // every file without touching other profiles or unscoped keys.
        val suffix = "_$profileId"
        preferenceNames.forEach { name ->
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val doomedKeys = preferences.all.keys.filter { key ->
                key.endsWith(suffix) ||
                    // Plugin scraper settings are the one store that embeds
                    // the profile id mid-key: "settings_<profileId>_<scraperId>".
                    (name == PLUGINS_PREFERENCES_NAME && key.startsWith("settings_${profileId}_"))
            }
            if (doomedKeys.isNotEmpty()) {
                preferences.edit().apply {
                    doomedKeys.forEach { key -> remove(key) }
                }.apply()
            }
        }
        // Plugin scraper code lives in a per-profile directory, not in
        // preferences; remove just the deleted profile's directory.
        context.filesDir
            .resolve(PLUGIN_SCRAPERS_DIR_NAME)
            .resolve(profileId.toString())
            .deleteRecursively()
    }
}
