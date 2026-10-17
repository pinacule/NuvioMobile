package com.nuvio.app.features.trailer

/**
 * Desktop stub: trailers play externally (see AppFeaturePolicy), so there is
 * nothing to resolve to an in-app stream.
 */
actual object TrailerPlaybackResolver {
    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? = null

    actual suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String? = null
}
