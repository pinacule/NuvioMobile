package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2pMagnetTest {
    @Test
    fun v1HashAndTrackersAreCanonicalEncodedAndDeduplicated() {
        val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val tracker = "udp://tracker.example:80/announce?key=hello world"
        val magnet = buildP2pMagnetUri(hash, listOf("", tracker, " ", tracker))

        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:${hash.lowercase()}"))
        assertEquals(1, magnet.windowed("&tr=".length).count { it == "&tr=" })
        assertTrue(magnet.endsWith("udp%3A%2F%2Ftracker.example%3A80%2Fannounce%3Fkey%3Dhello%20world"))
    }

    @Test
    fun v2HashUsesMultihashTopic() {
        val hash = "a".repeat(64)
        assertTrue(buildP2pMagnetUri(hash, emptyList()).contains("xt=urn:btmh:1220$hash"))
    }

    @Test
    fun invalidHashIsRejectedBeforeNativeWork() {
        assertFailsWith<IllegalArgumentException> {
            buildP2pMagnetUri("not-a-hash", emptyList())
        }
    }

    @Test
    fun base32HashDecodesToKnownHexVectors() {
        // SHA-1 of the empty string, in both representations.
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            canonicalP2pInfoHash("3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ"),
        )
        // All-zero and all-ones 20-byte hashes: the alphabet's edge symbols.
        assertEquals(
            "0000000000000000000000000000000000000000",
            canonicalP2pInfoHash("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        )
        assertEquals(
            "ffffffffffffffffffffffffffffffffffffffff",
            canonicalP2pInfoHash("77777777777777777777777777777777"),
        )
        // Sequential bytes 0..19: exercises multi-byte bit carry-over.
        assertEquals(
            "000102030405060708090a0b0c0d0e0f10111213",
            canonicalP2pInfoHash("AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT"),
        )
    }

    @Test
    fun base32HashIsCaseInsensitiveAndBuildsHexMagnet() {
        val magnet = buildP2pMagnetUri("3i42h3s6nnfq2msvx7xzkyayscx5qbyj", emptyList())
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709"))
    }

    @Test
    fun base32WithNonAlphabetCharactersIsRejected() {
        // '1' and '8' are outside the RFC 4648 base32 alphabet.
        assertFailsWith<IllegalArgumentException> {
            canonicalP2pInfoHash("3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBY1")
        }
        assertFailsWith<IllegalArgumentException> {
            canonicalP2pInfoHash("8I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ")
        }
    }

    @Test
    fun canonicalOrNullAcceptsBase32AndRejectsGarbage() {
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            canonicalP2pInfoHashOrNull("3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ"),
        )
        assertNull(canonicalP2pInfoHashOrNull("not-a-hash"))
        assertNull(canonicalP2pInfoHashOrNull(null))
    }
}
