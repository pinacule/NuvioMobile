package com.nuvio.app.core.network

internal object SupabaseEndpointConfig {
    private val primaryBaseUrl: String
        get() = ServerConfigurationRepository.active.value.backendUrl.normalizedBaseUrl()
    private val fallbackBaseUrl: String
        get() = ServerConfigurationRepository.active.value.fallbackBackendUrl.orEmpty().normalizedBaseUrl()

    val hasFallback: Boolean
        get() = fallbackBaseUrl.isNotBlank() && !fallbackBaseUrl.equals(primaryBaseUrl, ignoreCase = true)

    fun restEndpointUrls(): List<String> =
        endpointUrls("/rest/v1/")

    fun realtimeUrl(backendUrl: String): String {
        val base = backendUrl.normalizedBaseUrl()
        val socketBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
            else -> base
        }
        return "$socketBase/realtime/v1?nuvio_realtime=1"
    }

    fun fallbackUrlFor(requestUrl: String): String? {
        if (!hasFallback || primaryBaseUrl.isBlank()) return null
        val trimmedUrl = requestUrl.trim()
        if (!trimmedUrl.startsWith(primaryBaseUrl, ignoreCase = true)) return null
        return fallbackBaseUrl + trimmedUrl.substring(primaryBaseUrl.length)
    }

    fun shouldRetryWithFallback(requestUrl: String, cause: Throwable): Boolean {
        val fallbackUrl = fallbackUrlFor(requestUrl) ?: return false
        // A TLS/certificate failure is never an "origin failure": it means the
        // encrypted channel to the primary could not be established, and
        // re-sending the authenticated request anywhere else would reward
        // exactly the interference (hostile Wi-Fi, MITM) that broke it. Hard
        // fail instead. An http fallback is likewise never eligible — these
        // requests carry the account JWT.
        return isHttpsBackendUrl(fallbackUrl) &&
            !isTlsOrCertificateFailure(cause) &&
            cause.isOriginFailure()
    }

    fun shouldRetryWithFallback(requestUrl: String, statusCode: Int): Boolean {
        val fallbackUrl = fallbackUrlFor(requestUrl) ?: return false
        return isHttpsBackendUrl(fallbackUrl) && statusCode.isOriginFailureStatus()
    }

    private fun endpointUrls(path: String): List<String> =
        buildList {
            if (primaryBaseUrl.isNotBlank()) add(primaryBaseUrl + path)
            if (hasFallback) add(fallbackBaseUrl + path)
        }

    private fun String.normalizedBaseUrl(): String =
        trim().trimEnd('/')

    private fun Int.isOriginFailureStatus(): Boolean =
        this in 520..526 || this == 502 || this == 503 || this == 504

    private fun Throwable.isOriginFailure(): Boolean =
        originFailureMarkers.any(failureChainText()::contains)

    private val originFailureMarkers = listOf(
        "timeout",
        "timed out",
        "connection reset",
        "failed to connect",
        "unable to resolve host",
        "network is unreachable",
        "closed",
    )
}

/** True only for an https URL: the only fallback scheme an authenticated backend request may use. */
internal fun isHttpsBackendUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true)

/**
 * True when the failure chain names a TLS/certificate problem (SSL/TLS
 * handshake, certificate path validation, trust anchors). Such failures must
 * never be "recovered" by sending the request — credentials included — to a
 * different server.
 */
internal fun isTlsOrCertificateFailure(error: Throwable): Boolean =
    tlsFailureMarkers.any(error.failureChainText()::contains)

private val tlsFailureMarkers = listOf(
    "ssl",
    "tls",
    "certificate",
    "certpath",
    "cert path",
    "pkix",
    "trust anchor",
)

/** Class names + messages of the whole cause chain, lowercased, for marker matching. */
private fun Throwable.failureChainText(): String =
    generateSequence(this) { it.cause }
        .joinToString(separator = " ") { error ->
            listOfNotNull(error::class.simpleName, error.message).joinToString(separator = " ")
        }
        .lowercase()
