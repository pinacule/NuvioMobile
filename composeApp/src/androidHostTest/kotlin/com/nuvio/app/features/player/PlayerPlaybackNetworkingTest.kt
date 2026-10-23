package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPlaybackNetworkingTest {
    @Test
    fun sameHostAttaches() {
        assertTrue(shouldAttachAuthorization("cdn.example.com", "cdn.example.com"))
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertTrue(shouldAttachAuthorization("CDN.Example.COM", "cdn.example.com"))
    }

    @Test
    fun differentHostDoesNotAttach() {
        assertFalse(shouldAttachAuthorization("cdn.example.com", "attacker.example.net"))
    }

    @Test
    fun subdomainDoesNotAttach() {
        assertFalse(shouldAttachAuthorization("example.com", "cdn.example.com"))
        assertFalse(shouldAttachAuthorization("cdn.example.com", "example.com"))
    }

    @Test
    fun noPinnedHostDoesNotAttach() {
        assertFalse(shouldAttachAuthorization(null, "cdn.example.com"))
        assertFalse(shouldAttachAuthorization("", "cdn.example.com"))
        assertFalse(shouldAttachAuthorization("  ", "cdn.example.com"))
    }
}
