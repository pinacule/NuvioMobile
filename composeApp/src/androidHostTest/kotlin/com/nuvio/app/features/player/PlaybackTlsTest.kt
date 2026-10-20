package com.nuvio.app.features.player

import java.net.InetAddress
import java.net.Proxy
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTlsTest {
    private val rootCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()

    private fun serverCertificate(host: String, signedByRoot: Boolean): HeldCertificate =
        HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).apply {
            if (signedByRoot) signedBy(rootCa)
        }.build()

    /** A playback client whose "system" trust store holds only [rootCa]; every name resolves to loopback. */
    private fun client(): OkHttpClient {
        val system: X509TrustManager = HandshakeCertificates.Builder()
            .addTrustedCertificate(rootCa.certificate)
            .build()
            .trustManager
        val trustManager = LocalHostRelaxedTrustManager(system)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(PlaybackTls.hostnameVerifier)
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            // Straight to the local test server, whatever proxy the host JVM is configured with.
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    private fun serve(certificate: HeldCertificate, block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            block(server)
        }
    }

    private fun get(server: MockWebServer, host: String): String =
        client().newCall(Request.Builder().url("https://$host:${server.port}/stream").build())
            .execute().use { it.body!!.string() }

    @Test
    fun publicHostWithAValidCertificateIsAccepted() =
        serve(serverCertificate("cdn.example.com", signedByRoot = true)) { server ->
            assertEquals("ok", get(server, "cdn.example.com"))
        }

    @Test
    fun publicHostWithASelfSignedCertificateIsRejected() =
        serve(serverCertificate("cdn.example.com", signedByRoot = false)) { server ->
            // Before: every certificate was trusted, so anyone on the path could read the
            // Authorization header and token-bearing URLs of debrid streams.
            assertFailsWith<SSLHandshakeException> { get(server, "cdn.example.com") }
        }

    @Test
    fun publicHostWithAnotherHostsCertificateIsRejected() =
        serve(serverCertificate("other.example.com", signedByRoot = true)) { server ->
            assertFailsWith<SSLPeerUnverifiedException> { get(server, "cdn.example.com") }
        }

    @Test
    fun locallyNamedServerWithASelfSignedCertificateStillWorks() =
        serve(serverCertificate("jellyfin.lan", signedByRoot = false)) { server ->
            assertEquals("ok", get(server, "jellyfin.lan"))
        }

    @Test
    fun localNamesAreRecognizedWithoutResolving() {
        listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20",
            "169.254.3.4", "100.101.102.103", "::1", "[fe80::1]", "fd12:3456::1",
            "jellyfin", "nas.local", "media.lan", "box.home.arpa", "srv.internal", "pc.localdomain",
        ).forEach { assertTrue(isLocalNetworkHost(it), it) }
        listOf(
            "real-debrid.com", "cdn.example.com", "8.8.8.8", "172.32.0.1", "192.169.1.1",
            "100.128.0.1", "2001:db8::1", "local.example.com", "lan.example.com", "999.1.1.1", "",
        ).forEach { assertFalse(isLocalNetworkHost(it), it) }
    }

    @Test
    fun trailingDotsAndCaseDoNotChangeTheVerdict() {
        listOf("localhost.", "NAS.LOCAL", "Jellyfin").forEach { assertTrue(isLocalNetworkHost(it), it) }
        assertFalse(isLocalNetworkHost("cdn.example.com."))
    }

    @Test
    fun addressSpellingsAResolverReadsDifferentlyAreNeverLocal() {
        listOf(
            // inet_aton reads a leading-zero octet as octal: 010.0.0.1 is the public 8.0.0.1.
            "010.0.0.1", "0127.0.0.1", "192.168.01.1", "010.0.0.1.",
            // Dotless decimal and hex labels are whole addresses (both 8.8.8.8 here).
            "134744072", "0x8080808", "0X8080808", "0x", "2130706433",
            // Shortened dotted and IPv4-mapped forms are left to full verification.
            "127.1", "10.1", "::ffff:127.0.0.1", "[::ffff:10.0.0.1]",
        ).forEach { assertFalse(isLocalNetworkHost(it), it) }
    }
}
