package com.nuvio.app.features.trailer

import android.net.Uri
import com.nuvio.app.core.network.IPv4FirstDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .newCall(requestBuilder.build())
            .execute().use { response ->
                TrailerRequestResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    suspend fun buildPlaybackSource(
        bestManifest: ManifestCandidate?,
        bestProgressive: StreamCandidate?,
        bestVideo: StreamCandidate?,
        bestAudio: StreamCandidate?,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val bestCombinedIsManifest = bestManifest != null &&
            (bestProgressive == null || bestManifest.height > bestProgressive.height)

        val combinedUrl = if (bestCombinedIsManifest) {
            bestManifest.selectedVariantUrl
        } else {
            bestProgressive?.url
        }
        val combinedHeight: Int? = if (bestCombinedIsManifest) {
            bestManifest?.height
        } else {
            bestProgressive?.height
        }

        // Preference, not a hard gate: when the separated video track is lower-res
        // than the combined candidate, probe combined first (higher res wins);
        // if combined is unreachable, fall back to the (ungated) separated track.
        // Never return null while a reachable URL exists.
        val separatedLowerRes = combinedHeight != null && bestVideo != null &&
            bestVideo.height < combinedHeight
        val combinedProbeUrl: String? = if (separatedLowerRes) {
            combinedUrl?.let { resolveReachableUrlOrNull(it) }
        } else null
        if (!combinedProbeUrl.isNullOrBlank()) {
            return@withContext TrailerPlaybackSource(
                videoUrl = combinedProbeUrl,
                audioUrl = null,
            )
        }
        // combinedProbeUrl is null here: either separated is not lower-res (probe
        // combined lazily below) or combined was already probed and unreachable.
        val combinedFallbackUrl: String? = if (separatedLowerRes) {
            null
        } else {
            combinedUrl?.let { resolveReachableUrlOrNull(it) }
        }

        val separatedVideoUrl = bestVideo?.url?.let { resolveReachableUrlOrNull(it) }
        val (videoUrl, audioUrl) = if (!separatedVideoUrl.isNullOrBlank()) {
            val resolvedAudioUrl = bestAudio?.url?.let { resolveReachableUrlOrNull(it) }
            if (resolvedAudioUrl != null) {
                // Separated video+audio both reachable: use the pair.
                separatedVideoUrl to resolvedAudioUrl
            } else {
                // Audio failed: fall back to the muxed combined stream instead
                // of returning silent video.
                val combinedCandidateUrl = combinedFallbackUrl
                (combinedCandidateUrl ?: separatedVideoUrl) to null
            }
        } else {
            // No (usable) separated video: use combined (already probed above
            // when it was the preferred candidate).
            combinedFallbackUrl to null
        }
        val finalVideoUrl = videoUrl ?: return@withContext null

        TrailerPlaybackSource(
            videoUrl = finalVideoUrl,
            audioUrl = audioUrl,
        )
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val mnParam = uri.getQueryParameter("mn")
            ?: return if (isUrlReachable(url)) url else null
        val servers = mnParam.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (servers.size < 2) {
            return if (isUrlReachable(url)) url else null
        }

        val host = uri.host ?: return if (isUrlReachable(url)) url else null
        val candidates = mutableListOf(url)
        servers.forEachIndexed { index, server ->
            val altHost = host
                .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
            if (altHost != host) {
                candidates += uri.buildUpon().authority(altHost).build().toString()
            }
        }

        if (candidates.size == 1) {
            return if (isUrlReachable(candidates[0])) candidates[0] else null
        }

        val result = CompletableDeferred<String?>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        val inFlightCalls = Collections.synchronizedList(mutableListOf<Call>())
        val remaining = AtomicInteger(candidates.size)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate) { call -> inFlightCalls.add(call) }) {
                    result.complete(candidate)
                }
                if (remaining.decrementAndGet() == 0) {
                    // All probes finished with no success: don't wait out the
                    // full timeout.
                    result.complete(null)
                }
            }
        }

        return try {
            withTimeoutOrNull(2_000L) { result.await() }
        } finally {
            inFlightCalls.forEach { runCatching { it.cancel() } }
            probeScope.cancel()
        }
    }

    private fun isUrlReachable(url: String, trackCall: ((Call) -> Unit)? = null): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .headers(buildHeaders(defaultHeaders))
                .header("Range", "bytes=0-0")
                .build()

            val call = probeClient.newCall(request)
            trackCall?.invoke(call)
            call.execute().use { response ->
                response.code in 200..299
            }
        }.getOrDefault(false)
    }

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}