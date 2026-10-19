package com.nuvio.app.core.poster

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.ErrorResult

/**
 * Coil interceptor that detects failed image loads and retries with fallback URLs.
 *
 * Two extras keys are supported on [ImageRequest.memoryCacheKeyExtras]:
 * - [FALLBACK_URL_KEY]: a single fallback URL (legacy; treated as a one-element queue).
 * - [FALLBACK_URLS_KEY]: an ordered queue of fallback URLs, "\n"-delimited. On each failure
 *   the head of the queue is tried and the remainder is kept for the next failure, so a
 *   chain like dead-still -> TMDB still -> show art resolves automatically with no extra
 *   network probes: failure is detected at load time by Coil itself.
 */
class CustomPosterFallbackInterceptor : Interceptor {

    companion object {
        const val FALLBACK_URL_KEY = "custom_poster_fallback_url"
        const val FALLBACK_URLS_KEY = "custom_poster_fallback_urls"
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val extras = chain.request.memoryCacheKeyExtras
        val queue = fallbackQueue(extras)

        val result = chain.proceed()

        if (result is ErrorResult && queue.isNotEmpty()) {
            val remaining = queue.drop(1)
            val newExtras = if (remaining.isEmpty()) {
                extras - FALLBACK_URL_KEY - FALLBACK_URLS_KEY
            } else {
                (extras - FALLBACK_URL_KEY) + (FALLBACK_URLS_KEY to remaining.joinToString("\n"))
            }
            val fallbackRequest = chain.request.newBuilder()
                .data(queue.first())
                .memoryCacheKeyExtras(newExtras)
                .build()
            return chain.withRequest(fallbackRequest).proceed()
        }

        return result
    }

    private fun fallbackQueue(extras: Map<String, String>): List<String> {
        extras[FALLBACK_URLS_KEY]
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val single = extras[FALLBACK_URL_KEY]?.trim()?.takeIf { it.isNotBlank() }
        return if (single != null) listOf(single) else emptyList()
    }
}
