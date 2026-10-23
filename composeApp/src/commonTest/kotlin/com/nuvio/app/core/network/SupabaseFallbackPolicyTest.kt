package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupabaseFallbackPolicyTest {
    @Test
    fun sslHandshakeFailureIsTlsFailure() {
        val error = RuntimeException("SSL handshake aborted: ssl=0xb4000079, Failure in SSL library")
        assertTrue(isTlsOrCertificateFailure(error))
    }

    @Test
    fun certificatePathFailureIsTlsFailure() {
        val error = RuntimeException("CertPathValidatorException: Path does not chain with any of the trust anchors")
        assertTrue(isTlsOrCertificateFailure(error))
    }

    @Test
    fun pkixFailureIsTlsFailure() {
        val error = RuntimeException("PKIX path building failed: unable to find valid certification path to requested target")
        assertTrue(isTlsOrCertificateFailure(error))
    }

    @Test
    fun tlsFailureIsDetectedThroughTheCauseChain() {
        val error = RuntimeException(
            "Backend request failed",
            RuntimeException("TLS 1.3 handshake failure"),
        )
        assertTrue(isTlsOrCertificateFailure(error))
    }

    @Test
    fun plainConnectivityFailuresAreNotTlsFailures() {
        assertFalse(isTlsOrCertificateFailure(RuntimeException("Unable to resolve host api.example.com")))
        assertFalse(isTlsOrCertificateFailure(RuntimeException("Connection reset by peer")))
        assertFalse(isTlsOrCertificateFailure(RuntimeException("Connect timeout has expired")))
        assertFalse(isTlsOrCertificateFailure(RuntimeException("Failed to connect to /192.0.2.1:443")))
    }

    @Test
    fun onlyHttpsFallbackUrlsAreEligible() {
        assertTrue(isHttpsBackendUrl("https://backup.example.com"))
        assertTrue(isHttpsBackendUrl("HTTPS://backup.example.com"))
        assertFalse(isHttpsBackendUrl("http://backup.example.com"))
        assertFalse(isHttpsBackendUrl("http://192.168.1.10:8000"))
        assertFalse(isHttpsBackendUrl(""))
        assertFalse(isHttpsBackendUrl("backup.example.com"))
    }
}
