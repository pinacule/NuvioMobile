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
 * stream pick for the episode — targeting the item's ORIGINAL
 * [DownloadItem.downloadQuality] tier first, then the closest tiers, with
 * the English-audio preference applied WITHIN each tier — excluding the
 * dead URL, and applies the same debrid resolution the enqueue path uses
 * ([SeasonDownloads.resolvePlayable]). Known-oversize candidates are tried
 * only as a last resort, least-oversize first, so a dead 480p link no
 * longer silently resumes as a 1080p different encode.
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
    // Dub over sub, always — but within the item's quality constraint:
    // English-audio releases outrank others only inside a tier (see below).
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

    // Tries one candidate: resolve it and, when it yields a fresh URL,
    // return the refreshed item. [qualityLabel] is stored as the item's
    // downloadQuality — the tier the stream was picked at.
    suspend fun tryPick(group: AddonStreams, stream: StreamItem, qualityLabel: String?): DownloadItem? {
        val resolved = SeasonDownloads.resolvePlayable(stream, item.seasonNumber ?: 0, item.episodeNumber)
            ?: return null
        val freshUrl = resolved.playableDirectUrl?.trim()
            ?.takeIf { it.isSupportedDownloadUrl() && it != deadUrl }
            ?: return null
        return item.withFreshSource(group, resolved, freshUrl, qualityLabel)
    }

    // Quality-major: the item's original tier first, then the closest
    // tiers; within a tier, English-audio candidates first (when preferred),
    // then the rest in addon order. Known-oversize candidates are skipped
    // here and retried as a last resort below.
    val oversizeCandidates = mutableListOf<Pair<AddonStreams, StreamItem>>()
    for (quality in orderQualitiesByCloseness(qualities, targetQuality)) {
        val atTier = usable.filter { (_, stream) -> stream.streamResolution() == quality }
        for ((group, stream) in orderByEnglishPreference(atTier, preferEnglishAudio)) {
            if (AdaptiveStreamPicker.isKnownOversize(stream, quality)) {
                oversizeCandidates += group to stream
                continue
            }
            tryPick(group, stream, quality)?.let { return it }
        }
    }
    // Last resort, mirroring EpisodeDownloadPlanner.pickBestStreamAtQuality's
    // all-unparseable fallback: the quality loop above can only ever match a
    // stream whose resolution parses (distinctQualities drops the rest), so
    // a candidate with streamResolution() == null — e.g. an English dub whose
    // metadata carries no resolution token — was never tried and the retry
    // kept reusing the dead URL. When no parseable quality yielded a stream,
    // try the unparseable candidates with English first (when preferred),
    // preserving addon order. The picked stream's quality is unknown, so the
    // item keeps its requested downloadQuality — the same fallback
    // SeasonDownloads applies when it stores
    // `streamResolution() ?: template.streamResolution()`.
    for ((group, stream) in orderByEnglishPreference(usable, preferEnglishAudio)) {
        if (stream.streamResolution() != null) continue
        if (AdaptiveStreamPicker.isKnownOversize(stream, targetQuality)) {
            oversizeCandidates += group to stream
            continue
        }
        tryPick(group, stream, item.downloadQuality)?.let { return it }
    }
    // Final last resort: every sane candidate failed — retry the
    // known-oversize ones, least-oversize first, rather than giving up.
    for ((group, stream) in oversizeCandidates.sortedBy { (_, stream) ->
        AdaptiveStreamPicker.oversizeExcessBytes(stream, stream.streamResolution() ?: targetQuality)
    }) {
        tryPick(group, stream, stream.streamResolution() ?: item.downloadQuality)?.let { return it }
    }
    return null
}

/**
 * English-audio candidates first when [preferEnglishAudio] (the dub
 * preference, applied within a quality tier), preserving the original order
 * inside each group.
 */
private fun orderByEnglishPreference(
    candidates: List<Pair<AddonStreams, StreamItem>>,
    preferEnglishAudio: Boolean,
): List<Pair<AddonStreams, StreamItem>> {
    if (!preferEnglishAudio) return candidates
    val (english, rest) = candidates.partition { (_, stream) -> AdaptiveStreamPicker.hasEnglishAudio(stream) }
    return english + rest
}

/**
 * Re-picks a DIFFERENT torrent for [item] whose current torrent stalled (no
 * swarm progress within the stall window), excluding the stalled torrent
 * itself ([excludeInfoHash]). Candidates are ranked like the enqueue paths
 * rank them: English-audio first when preferred, then the item's original
 * [DownloadItem.downloadQuality] tier (closest tiers after), known-oversize
 * candidates last, then best-seeded.
 *
 * Returns the item re-pointed at the new torrent (magnet URL, info hash,
 * file index, trackers, filename hint), or null when no other torrent is
 * offered. The new torrent's bytes differ from the stalled one's, so
 * callers must discard the partial file and restart from zero.
 *
 * Note: unlike the season matcher, this re-pick does not re-apply the
 * conflicting-episode-marker filter — candidates come from the item's own
 * episode listing (its videoId), so they are that episode's streams by
 * construction.
 */
internal suspend fun refreshTorrentDownloadSource(
    item: DownloadItem,
    excludeInfoHash: String?,
): DownloadItem? {
    if (item.sourceKind != DownloadSourceKind.Torrent) return null
    // Idempotent, like refreshHttpDownloadSource: safe from background starts.
    runCatching { AddonRepository.initialize() }
    runCatching { PlayerSettingsRepository.ensureLoaded() }
    val preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
        PlayerSettingsRepository.uiState.value.preferredAudioLanguage,
    )
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
    val excluded = excludeInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val usable = candidates.flatMap { group ->
        group.streams.mapNotNull { stream ->
            val source = stream.torrentDownloadSource() ?: return@mapNotNull null
            if (excluded != null && source.infoHash.lowercase() == excluded) return@mapNotNull null
            Triple(group, stream, source)
        }
    }
    if (usable.isEmpty()) return null
    val tierOrder = orderQualitiesByCloseness(
        EpisodeDownloadPlanner.distinctQualities(usable.map { it.second }),
        item.downloadQuality,
    )
    val (group, stream, source) = usable.minWithOrNull(
        compareBy<Triple<AddonStreams, StreamItem, TorrentDownloadSource>> { (_, candidate, _) ->
            if (preferEnglishAudio && !AdaptiveStreamPicker.hasEnglishAudio(candidate)) 1 else 0
        }
            .thenBy { (_, candidate, _) ->
                candidate.streamResolution()
                    ?.let { tierOrder.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            }
            .thenBy { (_, candidate, _) ->
                if (AdaptiveStreamPicker.isKnownOversize(candidate, item.downloadQuality)) 1 else 0
            }
            .thenByDescending { (_, candidate, _) -> AdaptiveStreamPicker.parseSeeders(candidate) ?: -1 },
    ) ?: return null
    return item.copy(
        sourceUrl = source.magnetUri,
        torrentInfoHash = source.infoHash,
        torrentFileIdx = source.fileIdx,
        torrentTrackers = source.trackers,
        torrentFilenameHint = source.filenameHint,
        sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
        sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
        providerName = group.addonName,
        providerAddonId = group.addonId,
        streamTitle = stream.streamLabel,
        streamSubtitle = stream.streamSubtitle,
        sourceSubtitles = stream.externalSubtitles,
        downloadQuality = stream.streamResolution() ?: item.downloadQuality,
        sourceResolvedAtEpochMs = DownloadsClock.nowEpochMs(),
    )
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
