package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.withoutCredentialHeaders
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal object PlayerPlaybackNetworking {
    internal const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val DEFAULT_STREAM_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            // Verified TLS, relaxed only for hosts named as local (see PlaybackTls).
            .sslSocketFactory(PlaybackTls.sslContext.socketFactory, PlaybackTls.trustManager)
            .hostnameVerifier(PlaybackTls.hostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    private val loopbackPlaybackHttpClient: OkHttpClient by lazy {
        playbackHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestChain = if (isLoopbackHost(request.url.host)) {
                    chain.withReadTimeout(65, TimeUnit.SECONDS)
                } else {
                    chain
                }
                requestChain.proceed(request)
            }
            .build()
    }

    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        val baseClient = if (useLongReadTimeout) loopbackPlaybackHttpClient else playbackHttpClient
        val client = requestHeaders.headerValue("Authorization")?.let { authorization ->
            // This factory has no single original stream URL: its call sites
            // (PlatformPlaybackDataSourceFactory) receive only headers, and the
            // factory serves the video, a separate audio URL and subtitles.
            // Pin the token to the host of the first request this factory
            // instance makes. OkHttp strips Authorization on cross-host
            // redirects and network interceptors re-run per hop, so without
            // the host check a redirect to any other host would receive the
            // user's debrid token.
            val pinnedHost = AtomicReference<String?>(null)
            baseClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    pinnedHost.compareAndSet(null, request.url.host)
                    if (request.header("Authorization") == null &&
                        shouldAttachAuthorization(pinnedHost.get(), request.url.host)
                    ) {
                        chain.proceed(
                            request.newBuilder()
                                .header("Authorization", authorization)
                                .build()
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        } ?: baseClient

        return OkHttpDataSource.Factory(client).apply {
            // Credential headers never become factory defaults: a default
            // request property is sent on the FIRST request to whatever host
            // a data source opens — video, separate audio and external
            // subtitles alike — before any redirect logic runs, so a default
            // Authorization would leak to a subtitle or audio host on another
            // domain. The token reaches requests only through the host-pinned
            // network interceptor above, which attaches it for any request
            // type whose host matches the pinned host. Other credential
            // headers (Cookie, API keys) have no attach mechanism and are
            // dropped for this factory entirely.
            setDefaultRequestProperties(requestHeaders.withoutCredentialHeaders())
            if (requestHeaders.headerValue("User-Agent") == null) {
                setUserAgent(DEFAULT_USER_AGENT)
            }
        }
    }

    fun createDataSourceFactory(
        context: Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(defaultHeaders, useLongReadTimeout),
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
        followRedirects: Boolean = true,
    ): HttpURLConnection {
        val mergedHeaders = withDefaultUserAgent(headers)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = PlaybackTls.sslContext.socketFactory
                hostnameVerifier = PlaybackTls.hostnameVerifier
            }
            instanceFollowRedirects = followRedirects
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", mergedHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            mergedHeaders.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()

    private fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        val sanitized = sanitizeHeaders(headers)
        if (sanitized.headerValue("User-Agent") != null) return sanitized
        return DEFAULT_STREAM_HEADERS + sanitized
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}

/**
 * Whether a credential stripped by OkHttp may be re-attached to a request for
 * [requestHost]. Host equality only (case-insensitive), matching the codebase's
 * own host comparisons (e.g. isLoopbackPlaybackSource): subdomains and parent
 * domains do not match, and scheme/port are not part of the decision, so a
 * same-host redirect that changes port still counts as the same host.
 */
internal fun shouldAttachAuthorization(pinnedHost: String?, requestHost: String): Boolean =
    !pinnedHost.isNullOrBlank() && pinnedHost.equals(requestHost, ignoreCase = true)
