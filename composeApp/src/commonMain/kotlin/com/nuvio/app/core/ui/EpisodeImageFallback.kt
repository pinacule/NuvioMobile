package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/**
 * Builds a Coil model for an episode still with an ordered fallback chain:
 * primary still -> TMDB still -> show art.
 *
 * This exists because metadata sources (e.g. Cinemeta) routinely serve episode-still URLs
 * that later rot: the URL string is non-null, so a plain `thumbnail ?: fallback` Elvis never
 * engages and the card renders black. Worse, falling straight back to the show art gives
 * every episode of an affected show the SAME image. TMDB is the upstream source for most
 * of these stills, so its per-episode still is the natural middle tier.
 *
 * Failure is detected at load time by Coil itself (via [CustomPosterFallbackInterceptor],
 * which is registered on the app-wide Coil ImageLoader and reads the ordered queue from
 * [ImageRequest.memoryCacheKeyExtras]): no per-card network probes, no UI-thread work.
 *
 * Returns null when no URL is usable so callers can render a placeholder instead of
 * an empty image box.
 */
@Composable
fun rememberEpisodeImageModel(
    thumbnail: String?,
    tmdbStillUrl: String?,
    fallbackUrl: String?,
): Any? {
    val platformContext = LocalPlatformContext.current
    return remember(thumbnail, tmdbStillUrl, fallbackUrl, platformContext) {
        val queue = listOfNotNull(
            thumbnail?.trim()?.takeIf { it.isNotBlank() },
            tmdbStillUrl?.trim()?.takeIf { it.isNotBlank() },
            fallbackUrl?.trim()?.takeIf { it.isNotBlank() },
        ).distinct()
        when {
            queue.isEmpty() -> null
            queue.size == 1 -> queue.first()
            else -> ImageRequest.Builder(platformContext)
                .data(queue.first())
                .memoryCacheKeyExtras(
                    mapOf(
                        CustomPosterFallbackInterceptor.FALLBACK_URLS_KEY to
                            queue.drop(1).joinToString("\n")
                    )
                )
                .build()
        }
    }
}
