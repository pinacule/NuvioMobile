package com.nuvio.app.features.player

import com.nuvio.app.core.network.isCredentialHeader
import com.nuvio.app.core.network.withoutCredentialHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun credentialHeadersAreRecognizedCaseInsensitively() {
        assertTrue(isCredentialHeader("Authorization"))
        assertTrue(isCredentialHeader("authorization"))
        assertTrue(isCredentialHeader("Cookie"))
        assertTrue(isCredentialHeader("X-Api-Key"))
        assertTrue(isCredentialHeader(" X-Auth-Token "))
        assertFalse(isCredentialHeader("User-Agent"))
        assertFalse(isCredentialHeader("Referer"))
        assertFalse(isCredentialHeader("Accept"))
        assertFalse(isCredentialHeader("Range"))
    }

    @Test
    fun withoutCredentialHeadersKeepsOnlyNonCredentials() {
        val headers = mapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "User-Agent" to "Nuvio",
            "Referer" to "https://example.com/",
        )
        assertEquals(
            mapOf("User-Agent" to "Nuvio", "Referer" to "https://example.com/"),
            headers.withoutCredentialHeaders(),
        )
    }
}
