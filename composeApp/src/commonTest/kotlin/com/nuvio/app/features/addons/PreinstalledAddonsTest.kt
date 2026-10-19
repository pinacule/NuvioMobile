package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreinstalledAddonsTest {

    @Test
    fun `fresh install seeds both addons`() {
        assertEquals(
            listOf(TORRENTIO_PREINSTALLED_MANIFEST_URL, CINEMETA_PREINSTALLED_MANIFEST_URL),
            missingPreinstalledAddonUrls(emptyList()),
        )
    }

    @Test
    fun `exact torrentio url installed leaves only cinemeta missing`() {
        assertEquals(
            listOf(CINEMETA_PREINSTALLED_MANIFEST_URL),
            missingPreinstalledAddonUrls(listOf(TORRENTIO_PREINSTALLED_MANIFEST_URL)),
        )
    }

    @Test
    fun `differently configured torrentio variant counts as installed`() {
        // Any configured variant shares the host, so the existing install is left untouched.
        val variant = "https://torrentio.strem.fun/someothervalues/manifest.json"
        assertEquals(
            listOf(CINEMETA_PREINSTALLED_MANIFEST_URL),
            missingPreinstalledAddonUrls(listOf(variant)),
        )
    }

    @Test
    fun `cinemeta installed leaves only torrentio missing`() {
        assertEquals(
            listOf(TORRENTIO_PREINSTALLED_MANIFEST_URL),
            missingPreinstalledAddonUrls(listOf(CINEMETA_PREINSTALLED_MANIFEST_URL)),
        )
    }

    @Test
    fun `both families installed seeds nothing`() {
        assertTrue(
            missingPreinstalledAddonUrls(
                listOf(
                    "https://torrentio.strem.fun/qualityfilter=4k/manifest.json",
                    "https://v3-cinemeta.strem.io/manifest.json",
                    "https://some.other.addon/manifest.json",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `host matching is case-insensitive and scheme-agnostic`() {
        assertEquals("torrentio.strem.fun", addonManifestUrlHost("https://TORRENTIO.strem.fun/x/manifest.json"))
        assertEquals("v3-cinemeta.strem.io", addonManifestUrlHost("stremio://v3-cinemeta.strem.io/manifest.json"))
        assertEquals("v3-cinemeta.strem.io", addonManifestUrlHost("https://v3-cinemeta.strem.io/manifest.json?foo=bar"))
    }

    @Test
    fun `unrelated addons do not affect seeding`() {
        assertEquals(
            listOf(TORRENTIO_PREINSTALLED_MANIFEST_URL, CINEMETA_PREINSTALLED_MANIFEST_URL),
            missingPreinstalledAddonUrls(listOf("https://example.test/manifest.json")),
        )
    }
}
