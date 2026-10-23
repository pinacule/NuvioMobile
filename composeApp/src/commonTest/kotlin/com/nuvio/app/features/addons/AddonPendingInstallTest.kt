package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddonPendingInstallTest {

    @Test
    fun `staging a deep link url does not install anything`() {
        val addonsBefore = AddonRepository.uiState.value.addons

        AddonRepository.stagePendingInstallUrl("https://example.test/manifest.json")

        assertEquals("https://example.test/manifest.json", AddonRepository.pendingInstallUrl.value)
        // The security contract: staging alone must not fetch, install, persist,
        // or otherwise change the installed-addon state. Installation may only
        // happen when the user confirms on the Addons screen.
        assertEquals(addonsBefore, AddonRepository.uiState.value.addons)
        AddonRepository.consumePendingInstallUrl()
    }

    @Test
    fun `consume returns the staged url once and clears it`() {
        AddonRepository.stagePendingInstallUrl("https://example.test/manifest.json")

        assertEquals("https://example.test/manifest.json", AddonRepository.consumePendingInstallUrl())
        assertNull(AddonRepository.pendingInstallUrl.value)
        assertNull(AddonRepository.consumePendingInstallUrl())
    }

    @Test
    fun `staging trims whitespace and ignores blank urls`() {
        AddonRepository.stagePendingInstallUrl("  https://example.test/manifest.json  ")
        assertEquals("https://example.test/manifest.json", AddonRepository.consumePendingInstallUrl())

        AddonRepository.stagePendingInstallUrl("   ")
        assertNull(AddonRepository.pendingInstallUrl.value)
    }

    @Test
    fun `a newer deep link replaces the previously staged url`() {
        AddonRepository.stagePendingInstallUrl("https://first.test/manifest.json")
        AddonRepository.stagePendingInstallUrl("https://second.test/manifest.json")

        assertEquals("https://second.test/manifest.json", AddonRepository.consumePendingInstallUrl())
    }
}
