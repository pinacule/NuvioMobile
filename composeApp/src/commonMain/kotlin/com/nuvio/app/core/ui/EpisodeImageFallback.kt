package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/**
 * Builds a Coil model for an episode still that automatically retries with [fallbackUrl]
 * when the still URL is dead (HTTP 404, host gone, etc.). The retry is handled by
 * [CustomPosterFallbackInterceptor], which is registered on the app-wide Coil ImageLoader
 * and reads the fallback URL from [ImageRequest.memoryCacheKeyExtras].
 *
 * This exists because metadata sources (e.g. Cinemeta) routinely serve episode-still URLs
 * that later rot: the URL string is non-null, so a plain `thumbnail ?: fallback` Elvis never
 * engages and the card renders black. Routing the load through the interceptor fixes every
 * call site at once.
 *
 * Returns null when neither URL is usable so callers can render a placeholder instead of
 * an empty image box.
 */
@Composable
fun rememberEpisodeImageModel(
    thumbnail: String?,
    fallbackUrl: String?,
): Any? {
    val platformContext = LocalPlatformContext.current
    val cleanThumbnail = thumbnail?.trim()?.takeIf { it.isNotBlank() }
    val cleanFallback = fallbackUrl?.trim()?.takeIf { it.isNotBlank() }
    return remember(cleanThumbnail, cleanFallback, platformContext) {
        when {
            cleanThumbnail != null && cleanFallback != null && cleanFallback != cleanThumbnail ->
                ImageRequest.Builder(platformContext)
                    .data(cleanThumbnail)
                    .memoryCacheKeyExtras(
                        mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to cleanFallback)
                    )
                    .build()
            cleanThumbnail != null -> cleanThumbnail
            cleanFallback != null -> cleanFallback
            else -> null
        }
    }
}
