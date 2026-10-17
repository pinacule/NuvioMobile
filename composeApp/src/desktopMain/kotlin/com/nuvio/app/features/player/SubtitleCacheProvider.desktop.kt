package com.nuvio.app.features.player

/**
 * Desktop: no local caching needed — external handlers receive the remote
 * subtitle URLs unchanged (same as iOS).
 */
actual object SubtitleCacheProvider {
    actual suspend fun cacheForExternalPlayer(subtitles: List<SubtitleInput>): List<SubtitleInput>? =
        subtitles
}
