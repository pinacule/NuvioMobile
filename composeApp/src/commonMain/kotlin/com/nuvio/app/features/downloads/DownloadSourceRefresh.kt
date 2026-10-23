package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.AdaptiveStreamPicker
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.streamResolution
import kotlinx.coroutines.CancellationException

/**
 * HTTP status codes that mean "this link is dead, fetch a new one" rather than
 * "the server is having a bad day". Addon/debrid links expire; retrying the
 * same URL is pointless, but a freshly resolved URL usually works.
 */
internal val STALE_HTTP_STATUS_CODES = setOf(403, 404, 410)

/**
 * How many times IN A ROW one download may swap in a freshly resolved URL without
 * transferring any bytes in between before giving up (see [DownloadRecoveryBudget]).
 */
internal const val MAX_DOWNLOAD_SOURCE_REFRESHES = 2

/**
 * A queued HTTP link older than this is re-resolved when its transfer actually
 * starts (season downloads resolve every episode at enqueue, so later episodes
 * wait in the queue while their links age). Matches the debrid resolver's cache
 * TTL: re-resolving a younger link would come back from that cache unchanged.
 */
internal const val DOWNLOAD_SOURCE_MAX_AGE_MS = 15L * 60L * 1000L

/** When [DownloadItem.sourceUrl] was last resolved; items predating the field count from creation. */
internal val DownloadItem.sourceResolvedAt: Long
    get() = sourceResolvedAtEpochMs ?: createdAtEpochMs

/** True when an HTTP item's link is old enough to re-resolve before transferring. */
internal fun DownloadItem.hasAgedHttpSource(nowEpochMs: Long): Boolean =
    sourceKind == DownloadSourceKind.Http && nowEpochMs - sourceResolvedAt >= DOWNLOAD_SOURCE_MAX_AGE_MS

/**
 * A stream that can become an HTTP download: one with a direct link already, or a
 * debrid stream (client-resolved, or a torrent resolved with the user's debrid
 * account) that [SeasonDownloads.resolvePlayable] turns into one — the same
 * streams the enqueue path accepts.
 */
private fun StreamItem.isHttpDownloadCandidate(): Boolean =
    (!isTorrentStream && playableDirectUrl?.trim()?.isSupportedDownloadUrl() == true) ||
        DirectDebridPlaybackResolver.shouldResolveToPlayableStream(this)

/**
 * Resolves a fresh HTTP source URL for [item] whose current
 * [DownloadItem.sourceUrl] is dead (HTTP 403/404/410). Re-runs the planner's
 * stream pick for the episode — the originally picked quality first, then
 * closest — excluding the dead URL, and applies the same debrid resolution
 * the enqueue path uses ([SeasonDownloads.resolvePlayable]).
 *
 * Returns the item with the fresh URL, or null when no working link could be
 * found. The new link may point at a different encode: callers may keep the
 * partial file only when the fresh link reports the same total size as the
 * item's [DownloadItem.totalBytes], and must restart from zero otherwise.
 */
internal suspend fun refreshHttpDownloadSource(item: DownloadItem): DownloadItem? {
    if (item.sourceKind != DownloadSourceKind.Http) return null
    val deadUrl = item.sourceUrl.trim()
    if (deadUrl.isBlank()) return null
    // Idempotent: makes the automatic retry work even when the process was
    // started in the background (JobScheduler/WorkManager) without the UI.
    runCatching { AddonRepository.initialize() }
    runCatching { PlayerSettingsRepository.ensureLoaded() }
    // Dub over sub, always: when re-resolving a dead link, English-audio
    // releases outrank everything else, regardless of quality tier.
    val preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
        PlayerSettingsRepository.uiState.value.preferredAudioLanguage,
    )
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
            stream.isHttpDownloadCandidate() && stream.playableDirectUrl?.trim() != deadUrl
        }
    if (usable.isEmpty()) return null
    val qualities = EpisodeDownloadPlanner.distinctQualities(usable.map { it.second })
    // English-audio releases first (all qualities, closest-first), then the rest —
    // a 480p dub outranks a 1080p sub-only release when the user prefers English.
    val partitions = if (preferEnglishAudio) {
        val (english, rest) = usable.partition { (_, stream) -> AdaptiveStreamPicker.hasEnglishAudio(stream) }
        listOf(english, rest).filter { it.isNotEmpty() }
    } else {
        listOf(usable)
    }
    for (partition in partitions) {
        for (quality in orderQualitiesByCloseness(qualities, targetQuality)) {
            for ((group, stream) in partition) {
                if (stream.streamResolution() != quality) continue
                val resolved = SeasonDownloads.resolvePlayable(stream, item.seasonNumber ?: 0, item.episodeNumber)
                    ?: continue
                val freshUrl = resolved.playableDirectUrl?.trim()
                    ?.takeIf { it.isSupportedDownloadUrl() && it != deadUrl }
                    ?: continue
                return item.withFreshSource(group, resolved, freshUrl, quality)
            }
        }
    }
    // Last resort, mirroring EpisodeDownloadPlanner.pickBestStreamAtQuality's
    // all-unparseable fallback: the quality loop above can only ever match a
    // stream whose resolution parses (distinctQualities drops the rest), so
    // a candidate with streamResolution() == null — e.g. an English dub whose
    // metadata carries no resolution token — was never tried and the retry
    // kept reusing the dead URL. When no parseable quality yielded a stream,
    // try the unparseable candidates in the same partition order (English
    // first when the user prefers English audio), preserving addon order
    // within a partition as the loop above does. The picked stream's quality
    // is unknown, so the item keeps its requested downloadQuality — the same
    // fallback SeasonDownloads applies when it stores
    // `streamResolution() ?: template.streamResolution()`.
    for (partition in partitions) {
        for ((group, stream) in partition) {
            if (stream.streamResolution() != null) continue
            val resolved = SeasonDownloads.resolvePlayable(stream, item.seasonNumber ?: 0, item.episodeNumber)
                ?: continue
            val freshUrl = resolved.playableDirectUrl?.trim()
                ?.takeIf { it.isSupportedDownloadUrl() && it != deadUrl }
                ?: continue
            return item.withFreshSource(group, resolved, freshUrl, item.downloadQuality)
        }
    }
    return null
}

/**
 * Re-resolves the SAME stream [item] was enqueued from (same addon, same release
 * label) for a fresh URL, e.g. when a queued link aged while waiting for a slot.
 * Unlike [refreshHttpDownloadSource] the current URL is not presumed dead: the
 * result may carry the very same URL. Returns null when that stream is no longer
 * offered or does not resolve; callers then keep the current URL.
 */
internal suspend fun reResolveHttpDownloadSource(item: DownloadItem): DownloadItem? {
    if (item.sourceKind != DownloadSourceKind.Http) return null
    val addonId = item.providerAddonId ?: return null
    runCatching { AddonRepository.initialize() }
    val candidates = try {
        EpisodeDownloadPlanner.fetchAddonStreams(
            contentType = item.contentType,
            videoId = item.videoId,
            preferredAddonId = addonId,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        return null
    }
    for (group in candidates) {
        if (group.addonId != addonId) continue
        for (stream in group.streams) {
            if (stream.streamLabel != item.streamTitle || stream.streamSubtitle != item.streamSubtitle) continue
            if (!stream.isHttpDownloadCandidate()) continue
            val resolved = SeasonDownloads.resolvePlayable(stream, item.seasonNumber ?: 0, item.episodeNumber)
                ?: continue
            val freshUrl = resolved.playableDirectUrl?.trim()?.takeIf { it.isSupportedDownloadUrl() } ?: continue
            return item.withFreshSource(group, resolved, freshUrl, item.downloadQuality)
        }
    }
    return null
}

private fun DownloadItem.withFreshSource(
    group: AddonStreams,
    resolved: StreamItem,
    freshUrl: String,
    quality: String?,
): DownloadItem = copy(
    sourceUrl = freshUrl,
    sourceHeaders = sanitizeRequestHeaders(resolved.behaviorHints.proxyHeaders?.request),
    sourceResponseHeaders = sanitizeResponseHeaders(resolved.behaviorHints.proxyHeaders?.response),
    providerName = group.addonName,
    providerAddonId = group.addonId,
    streamTitle = resolved.streamLabel,
    streamSubtitle = resolved.streamSubtitle,
    sourceSubtitles = resolved.externalSubtitles,
    downloadQuality = quality,
    sourceResolvedAtEpochMs = DownloadsClock.nowEpochMs(),
)

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
