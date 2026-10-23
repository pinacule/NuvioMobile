package com.nuvio.app.core.storage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlatformLocalAccountDataCleanerTest {
    private lateinit var context: Context

    private val sweptFiles = listOf(
        "nuvio_watched",
        "nuvio_downloads",
        "nuvio_plugins",
        "nuvio_profile_pin_cache",
        "nuvio_tmdb_settings",
    )

    @BeforeTest
    fun initialize() {
        context = RuntimeEnvironment.getApplication()
        PlatformLocalAccountDataCleaner.initialize(context)
        sweptFiles.forEach { prefs(it).edit().clear().commit() }
        prefs("nuvio_sentry_settings").edit().clear().commit()
    }

    @AfterTest
    fun clearState() {
        sweptFiles.forEach { prefs(it).edit().clear().commit() }
        prefs("nuvio_sentry_settings").edit().clear().commit()
        scraperRoot().deleteRecursively()
    }

    @Test
    fun removeProfileDeletesOnlyTheDeletedProfilesKeysAcrossFiles() {
        // Explicit-profile-id payload form (watched_payload_<id>).
        prefs("nuvio_watched").edit()
            .putString("watched_payload_2", "deleted profile")
            .putString("watched_payload_3", "surviving profile")
            .commit()
        // ProfileScopedKey form (<baseKey>_<id>).
        prefs("nuvio_downloads").edit()
            .putString("downloads_payload_2", "deleted profile")
            .putString("downloads_payload_3", "surviving profile")
            .putString("downloads_payload", "unscoped legacy key")
            .commit()
        prefs("nuvio_profile_pin_cache").edit()
            .putString("profile_pin_cache_2", "deleted profile")
            .putString("profile_pin_cache_3", "surviving profile")
            .commit()

        PlatformLocalAccountDataCleaner.removeProfile(2)

        assertFalse(prefs("nuvio_watched").contains("watched_payload_2"))
        assertEquals("surviving profile", prefs("nuvio_watched").getString("watched_payload_3", null))
        assertFalse(prefs("nuvio_downloads").contains("downloads_payload_2"))
        assertEquals("surviving profile", prefs("nuvio_downloads").getString("downloads_payload_3", null))
        assertEquals("unscoped legacy key", prefs("nuvio_downloads").getString("downloads_payload", null))
        assertFalse(prefs("nuvio_profile_pin_cache").contains("profile_pin_cache_2"))
        assertEquals("surviving profile", prefs("nuvio_profile_pin_cache").getString("profile_pin_cache_3", null))
    }

    @Test
    fun removeProfileDeletesPluginSettingsMidKeysAndScraperCodeDir() {
        prefs("nuvio_plugins").edit()
            .putString("plugins_state_2", "deleted profile")
            .putString("plugins_state_3", "surviving profile")
            // The one mid-key form: settings_<profileId>_<scraperId>.
            .putString("settings_2_scraperA", "deleted profile")
            .putString("settings_3_scraperA", "surviving profile")
            .commit()
        val deletedDir = scraperRoot().resolve("2").apply { mkdirs() }
        deletedDir.resolve("scraper.js").writeText("code")
        val survivingDir = scraperRoot().resolve("3").apply { mkdirs() }
        survivingDir.resolve("scraper.js").writeText("code")

        PlatformLocalAccountDataCleaner.removeProfile(2)

        assertFalse(prefs("nuvio_plugins").contains("plugins_state_2"))
        assertFalse(prefs("nuvio_plugins").contains("settings_2_scraperA"))
        assertEquals("surviving profile", prefs("nuvio_plugins").getString("plugins_state_3", null))
        assertEquals("surviving profile", prefs("nuvio_plugins").getString("settings_3_scraperA", null))
        assertFalse(deletedDir.exists())
        assertTrue(survivingDir.exists())
    }

    @Test
    fun removeProfileLeavesDeliberatelyKeptFilesUntouched() {
        // nuvio_sentry_settings is a documented wipe exclusion (device
        // config), so it is outside the sweep inventory entirely.
        prefs("nuvio_sentry_settings").edit().putBoolean("enabled", false).commit()

        PlatformLocalAccountDataCleaner.removeProfile(2)

        assertTrue(prefs("nuvio_sentry_settings").contains("enabled"))
        assertFalse(prefs("nuvio_sentry_settings").getBoolean("enabled", true))
    }

    @Test
    fun wipeClearsNewlyInventoriedCredentialFiles() {
        prefs("nuvio_tmdb_settings").edit()
            .putString("tmdb_api_key_2", "secret")
            .commit()

        PlatformLocalAccountDataCleaner.wipe()

        assertTrue(prefs("nuvio_tmdb_settings").all.isEmpty())
    }

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun scraperRoot(): File = context.filesDir.resolve("nuvio_plugin_scrapers")
}
