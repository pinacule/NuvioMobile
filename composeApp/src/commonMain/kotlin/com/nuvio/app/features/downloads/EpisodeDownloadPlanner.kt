package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AdaptiveStreamPicker
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.streamResolution
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Where a multi-episode download starts, derived from watch progress. */
internal data class EpisodeAnchor(
    val season: Int,
    val episode: Int,
    val isCompleted: Boolean,
    val lastUpdatedEpochMs: Long,
)

internal data class AddonStreams(
    val manifest: AddonManifest,
    val addonName: String,
    val addonId: String,
    val addonLogo: String?,
    val streams: List<StreamItem>,
)

/**
 * Shared planning logic for the long-press poster download options and the
 * next-episode preload: anchor resolution, episode ranges, quality helpers and
 * raw stream fetching (no template needed, unlike [SeasonDownloads]).
 */
internal object EpisodeDownloadPlanner {
    const val DEFAULT_QUALITY = "720p"

    /** Most recently touched episode of the series, or null when never started. */
    fun latestAnchor(entries: List<WatchProgressEntry>, parentMetaId: String): EpisodeAnchor? =
        entries
            .filter { it.parentMetaId == parentMetaId && it.seasonNumber != null && it.episodeNumber != null }
            .maxByOrNull { it.lastUpdatedEpochMs }
            ?.let { EpisodeAnchor(it.seasonNumber!!, it.episodeNumber!!, it.isCompleted, it.lastUpdatedEpochMs) }

    /**
     * First video to download from: the latest in-progress episode, the episode
     * after a completed one (season rollover included), or the first released
     * episode when the show was never started.
     */
    fun resolveStartVideo(videos: List<MetaVideo>, anchor: EpisodeAnchor?): MetaVideo? {
        val ordered = orderedReleases(videos)
        if (ordered.isEmpty()) return null
        if (anchor == null) return ordered.first()
        val idx = ordered.indexOfFirst { it.season == anchor.season && it.episode == anchor.episode }
        if (idx < 0) return ordered.first()
        return if (anchor.isCompleted) ordered.getOrNull(idx + 1) else ordered[idx]
    }

    /**
     * Released episodes from [startVideo] to the end of its season,
     * excluding episodes [isDownloaded] already covers.
     */
    fun restOfSeasonVideos(
        videos: List<MetaVideo>,
        startVideo: MetaVideo,
        isDownloaded: (season: Int, episode: Int) -> Boolean = { _, _ -> false },
    ): List<MetaVideo> {
        val season = startVideo.season ?: return emptyList()
        val episode = startVideo.episode ?: return emptyList()
        return videos
            .filter { it.season == season && it.available && (it.episode ?: 0) >= episode }
            .filterNot { isDownloaded(season, it.episode ?: 0) }
            .sortedBy { it.episode }
    }

    /**
     * Next [count] released episodes starting at [startVideo] (may cross seasons).
     *
     * Smart window: when episodes of the start video's season are already
     * downloaded, the window starts after max(current episode, highest downloaded
     * episode) — "Download next 3" on E1 with E1–E3 saved yields E4, E5, E6
     * instead of re-requesting what's already there. Already-downloaded
     * episodes inside the window are filtered out (gap safety). With nothing
     * downloaded the legacy window (current episode included) is kept.
     */
    fun nextNVideos(
        videos: List<MetaVideo>,
        startVideo: MetaVideo,
        count: Int,
        isDownloaded: (season: Int, episode: Int) -> Boolean = { _, _ -> false },
    ): List<MetaVideo> {
        val ordered = orderedReleases(videos)
        val idx = ordered.indexOfFirst { it.id == startVideo.id }
        if (idx < 0) return emptyList()
        val season = startVideo.season
        val highestDownloaded = if (season == null) 0 else ordered
            .asSequence()
            .filter { it.season == season && it.episode != null && isDownloaded(season, it.episode) }
            .maxOfOrNull { it.episode ?: 0 } ?: 0
        if (highestDownloaded <= 0 || season == null) {
            // Legacy window (current episode included), minus anything already saved.
            return ordered.drop(idx).take(count).filterNot { video ->
                val s = video.season
                val e = video.episode
                s != null && e != null && isDownloaded(s, e)
            }
        }
        val startAfter = kotlin.math.max(startVideo.episode ?: 0, highestDownloaded)
        return ordered
            .asSequence()
            .filter { video ->
                val s = video.season ?: return@filter false
                val e = video.episode ?: return@filter false
                (s > season || (s == season && e > startAfter)) && !isDownloaded(s, e)
            }
            .take(count)
            .toList()
    }

    /**
     * Explicit episode selection for the custom download picker: resolves checked
     * (season, episode) pairs to [MetaVideo]s in canonical release order.
     * Additive seam for the custom picker — the stream-picking path stays shared:
     * the caller still picks the template via [pickBestStreamAtQuality] and hands
     * the result to [SeasonDownloads.enqueueVideos], exactly like the presets.
     */
    fun explicitVideos(
        videos: List<MetaVideo>,
        selected: Set<Pair<Int, Int>>,
    ): List<MetaVideo> =
        videos.filter { video ->
            val s = video.season
            val e = video.episode
            s != null && e != null && video.available && selected.contains(s to e)
        }.sortedWith(compareBy({ it.season!! }, { it.episode!! }))

    /** Distinct stream resolutions, best quality first. */
    fun distinctQualities(streams: List<StreamItem>): List<String> =
        streams.mapNotNull { it.streamResolution() }
            .distinct()
            .sortedByDescending { qualityRank(it) }

    /** Closest offered quality to [target] (exact match wins, ties prefer higher). */
    fun closestQuality(available: List<String>, target: String): String? {
        if (available.isEmpty()) return null
        if (target in available) return target
        val targetRank = qualityRank(target)
        return available.minByOrNull { kotlin.math.abs(qualityRank(it) - targetRank) }
    }

    /**
     * Best stream at [quality]: English-audio first (dub over sub, always — when
     * [preferEnglishAudio]), then torrent streams (they carry the P2P path the
     * download manager prefers), then best-seeded. Addon ordering breaks ties.
     */
    fun pickBestStreamAtQuality(
        streams: List<StreamItem>,
        quality: String,
        preferEnglishAudio: Boolean = false,
    ): StreamItem? {
        val pool = streams.filter { it.streamResolution() == quality }
        if (pool.isEmpty()) return null
        return pool.sortedWith(
            compareBy<StreamItem> { if (preferEnglishAudio && AdaptiveStreamPicker.hasEnglishAudio(it)) 0 else 1 }
                .thenByDescending { it.isTorrentStream }
                .thenByDescending { AdaptiveStreamPicker.parseSeeders(it) ?: -1 },
        ).firstOrNull()
    }

    /**
     * Raw streams for one episode video from every enabled stream addon
     * (the [preferredAddonId]'s streams come first).
     */
    suspend fun fetchAddonStreams(
        contentType: String,
        videoId: String,
        preferredAddonId: String? = null,
    ): List<AddonStreams> = coroutineScope {
        val targets = AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(contentType, videoId)) return@mapNotNull null
            Triple(
                manifest,
                addon.displayTitle.ifBlank { manifest.name },
                addon.streamAddonInstanceId(manifest.id),
            )
        }.sortedBy { (_, _, addonId) -> if (addonId == preferredAddonId) 0 else 1 }
        targets.map { (manifest, addonName, addonId) ->
            async {
                val streams = SeasonDownloads.fetchStreamsForVideo(
                    manifest = manifest,
                    contentType = contentType,
                    videoId = videoId,
                    addonName = addonName,
                    addonId = addonId,
                    addonLogo = manifest.logoUrl,
                )
                AddonStreams(
                    manifest = manifest,
                    addonName = addonName,
                    addonId = addonId,
                    addonLogo = manifest.logoUrl,
                    streams = streams,
                )
            }
        }.awaitAll()
    }

    /**
     * Meta videos plus the resolved start video for poster-initiated flows
     * ("Choose stream" / download). Null video when there is nothing to start from.
     */
    suspend fun resolveSeriesStartVideo(
        parentMetaType: String,
        parentMetaId: String,
    ): Pair<List<MetaVideo>, MetaVideo?> {
        val meta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
            ?: MetaDetailsRepository.fetch(parentMetaType, parentMetaId)
        val videos = meta?.videos.orEmpty()
        WatchProgressRepository.ensureLoaded()
        val anchor = latestAnchor(WatchProgressRepository.uiState.value.entries, parentMetaId)
        return videos to resolveStartVideo(videos, anchor)
    }

    private fun orderedReleases(videos: List<MetaVideo>): List<MetaVideo> =
        videos.filter { it.season != null && it.episode != null && it.available }
            .sortedWith(compareBy({ it.season!! }, { it.episode!! }))

    private fun qualityRank(quality: String): Int = when (quality) {
        "2160p" -> 6
        "1440p" -> 5
        "1080p" -> 4
        "720p" -> 3
        "576p" -> 2
        "480p" -> 1
        "360p" -> 0
        else -> -1
    }
}
