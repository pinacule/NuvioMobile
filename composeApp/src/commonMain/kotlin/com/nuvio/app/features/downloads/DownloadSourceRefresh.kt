package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.streams.streamResolution
import kotlinx.coroutines.CancellationException

/**
 * HTTP status codes that mean "this link is dead, fetch a new one" rather than
 * "the server is having a bad day". Addon/debrid links expire; retrying the
 * same URL is pointless, but a freshly resolved URL usually works.
 */
internal val STALE_HTTP_STATUS_CODES = setOf(403, 404, 410)

/** How many times one download may swap in a freshly resolved URL before giving up. */
internal const val MAX_DOWNLOAD_SOURCE_REFRESHES = 2

/**
 * Resolves a fresh HTTP source URL for [item] whose current
 * [DownloadItem.sourceUrl] is dead (HTTP 403/404/410). Re-runs the planner's
 * stream pick for the episode — the originally picked quality first, then
 * closest — excluding the dead URL, and applies the same debrid resolution
 * the enqueue path uses ([SeasonDownloads.resolvePlayable]).
 *
 * Returns the item with the fresh URL, or null when no working link could be
 * found. Progress is intentionally not carried over: the new link may point at
 * a different encode, so callers must drop the partial file and restart from
 * zero rather than resuming bytes across files.
 */
internal suspend fun refreshHttpDownloadSource(item: DownloadItem): DownloadItem? {
    if (item.sourceKind != DownloadSourceKind.Http) return null
    val deadUrl = item.sourceUrl.trim()
    if (deadUrl.isBlank()) return null
    // Idempotent: makes the automatic retry work even when the process was
    // started in the background (JobScheduler/WorkManager) without the UI.
    runCatching { AddonRepository.initialize() }
    val targetQuality = item.downloadQuality?.trim()?.takeIf { it.isNotBlank() }
    val candidates = try {
        EpisodeDownloadPlanner.fetchAddonStreams(
            contentType = item.contentType,
            videoId = item.videoId,
            preferredAddonId = item.providerAddonId,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        return null
    }
    val usable = candidates
        .flatMap { group -> group.streams.map { stream -> group to stream } }
        .filter { (_, stream) ->
            !stream.isTorrentStream &&
                stream.playableDirectUrl?.trim()?.isSupportedDownloadUrl() == true &&
                stream.playableDirectUrl?.trim() != deadUrl
        }
    if (usable.isEmpty()) return null
    val qualities = EpisodeDownloadPlanner.distinctQualities(usable.map { it.second })
    for (quality in orderQualitiesByCloseness(qualities, targetQuality)) {
        for ((group, stream) in usable) {
            if (stream.streamResolution() != quality) continue
            val resolved = SeasonDownloads.resolvePlayable(stream, item.seasonNumber, item.episodeNumber)
                ?: continue
            val freshUrl = resolved.playableDirectUrl?.trim()
                ?.takeIf { it.isSupportedDownloadUrl() && it != deadUrl }
                ?: continue
            return item.copy(
                sourceUrl = freshUrl,
                sourceHeaders = sanitizeRequestHeaders(resolved.behaviorHints.proxyHeaders?.request),
                sourceResponseHeaders = sanitizeResponseHeaders(resolved.behaviorHints.proxyHeaders?.response),
                providerName = group.addonName,
                providerAddonId = group.addonId,
                streamTitle = resolved.streamLabel,
                streamSubtitle = resolved.streamSubtitle,
                sourceSubtitles = resolved.externalSubtitles,
                downloadQuality = quality,
            )
        }
    }
    return null
}

/** [target] first (when offered), then the remaining qualities by closeness to it. */
private fun orderQualitiesByCloseness(qualities: List<String>, target: String?): List<String> {
    if (target == null) return qualities
    val remaining = qualities.filter { it != target }.toMutableList()
    val ordered = mutableListOf<String>()
    if (target in qualities) ordered += target
    while (remaining.isNotEmpty()) {
        val next = EpisodeDownloadPlanner.closestQuality(remaining, target) ?: break
        ordered += next
        remaining.remove(next)
    }
    return ordered
}
