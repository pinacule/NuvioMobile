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
     * after a completed one (season rollover included), or — when there is no
     * usable anchor — the first regular (season >= 1) episode. Some catalogs
     * lead with season-0 specials (e.g. an unaired pilot with no streams at
     * all), so an unanchored start skips them; a specials-only series still
     * starts at its first special.
     */
    fun resolveStartVideo(videos: List<MetaVideo>, anchor: EpisodeAnchor?): MetaVideo? {
        val ordered = orderedReleases(videos)
        if (ordered.isEmpty()) return null
        if (anchor != null) {
            val idx = ordered.indexOfFirst { it.season == anchor.season && it.episode == anchor.episode }
            if (idx >= 0) {
                return if (anchor.isCompleted) ordered.getOrNull(idx + 1) else ordered[idx]
            }
        }
        return ordered.firstOrNull { (it.season ?: 0) >= 1 } ?: ordered.first()
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
     * The window walks episodes in order FROM [startVideo] and picks the next
     * [count] that are not fully downloaded: the current episode is included
     * whenever it isn't finished — "Download next 3" on E5 with E1–E3 saved
     * yields E5, E6, E7, not E6–E8. [isFullyDownloaded] must report only
     * completed downloads; an in-progress or paused download counts as not
     * finished, so its episode stays in the window (the enqueue path treats
     * already-active episodes as covered and never duplicates them). Fully
     * downloaded episodes inside the window are skipped and the take extends
     * past them, filling gaps in episode order.
     */
    fun nextNVideos(
        videos: List<MetaVideo>,
        startVideo: MetaVideo,
        count: Int,
        isFullyDownloaded: (season: Int, episode: Int) -> Boolean = { _, _ -> false },
    ): List<MetaVideo> {
        val ordered = orderedReleases(videos)
        val idx = ordered.indexOfFirst { it.id == startVideo.id }
        if (idx < 0) return emptyList()
        return ordered.drop(idx)
            .filterNot { video ->
                val s = video.season
                val e = video.episode
                s != null && e != null && isFullyDownloaded(s, e)
            }
            .take(count)
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

    /**
     * Closest offered quality to [target] (exact match wins, ties prefer
     * lower — the tie-break [AdaptiveStreamPicker] and [SeasonDownloads]
     * use for their closest-quality fallbacks).
     */
    fun closestQuality(available: List<String>, target: String): String? {
        if (available.isEmpty()) return null
        if (target in available) return target
        val targetRank = qualityRank(target)
        return available.minWithOrNull(
            compareBy<String> { kotlin.math.abs(qualityRank(it) - targetRank) }
                .thenBy { qualityRank(it) },
        )
    }

    /**
     * Best stream for a download at the user's requested [quality].
     *
     * The requested quality is the PRIMARY key — also for the dub rule.
     * When [preferEnglishAudio], an English-audio release still beats any
     * non-English one (dub over sub, always — a 480p dub beats a 1080p sub),
     * but among dubs the requested tier wins first; a dub at another tier is
     * only the fallback when NO dub exists at the requested tier, and then
     * the CLOSEST tier (ties to the lower) supplies it — never simply the
     * best-seeded dub across all tiers. (Playback's pickBestSeeded ranks
     * dubs across qualities; downloads deliberately do not: the user picked
     * this quality explicitly, for files that persist.) Only when no dub
     * exists anywhere does the pick fall back to the best stream at
     * [quality]: English-audio first, then torrent, then best-seeded.
     * Addon ordering breaks ties.
     *
     * Known-oversize candidates (past their tier's sane ceiling, see
     * [AdaptiveStreamPicker.isKnownOversize]) are deprioritized in every
     * pool; a pool that is entirely oversize yields its least-oversize
     * member instead of nothing.
     *
     * Last resort, mirroring [SeasonDownloads.pickMatchingEpisodeStream]:
     * when NO candidate's resolution parses, no quality can be resolved at
     * all, so all candidates form the pool (ranked as above) instead of
     * reporting "no streams" for an episode the season path would download
     * from its own unparseable pool. When some other quality IS parseable,
     * the requested quality genuinely isn't offered and the pick stays null.
     */
    fun pickBestStreamAtQuality(
        streams: List<StreamItem>,
        quality: String,
        preferEnglishAudio: Boolean = false,
    ): StreamItem? {
        if (preferEnglishAudio) {
            val english = streams.filter { AdaptiveStreamPicker.hasEnglishAudio(it) }
            if (english.isNotEmpty()) {
                pickEnglishForDownload(english, quality)?.let { return it }
            }
        }
        val exactPool = streams.filter { it.streamResolution() == quality }
        val pool = when {
            exactPool.isNotEmpty() -> exactPool
            streams.none { it.streamResolution() != null } -> streams
            else -> return null
        }
        return rankDownloadPool(pool, quality, englishFirst = preferEnglishAudio)
    }

    /**
     * The dub pick for downloads: dubs at [quality] first; when none exist
     * there, dubs at the closest other tier (ties to the lower tier); dubs
     * whose resolution cannot be parsed last. Null only when [english] is
     * empty (the caller guarantees it is not).
     */
    private fun pickEnglishForDownload(english: List<StreamItem>, quality: String): StreamItem? {
        val atRequested = english.filter { it.streamResolution() == quality }
        if (atRequested.isNotEmpty()) return rankDownloadPool(atRequested, quality, englishFirst = false)
        var remainingTiers = english.mapNotNull { it.streamResolution() }.distinct()
        while (remainingTiers.isNotEmpty()) {
            val tier = closestQuality(remainingTiers, quality) ?: break
            val tierPool = english.filter { it.streamResolution() == tier }
            rankDownloadPool(tierPool, tier, englishFirst = false)?.let { return it }
            remainingTiers = remainingTiers - tier
        }
        val unparseable = english.filter { it.streamResolution() == null }
        if (unparseable.isNotEmpty()) return rankDownloadPool(unparseable, quality, englishFirst = false)
        return null
    }

    /**
     * Ranks one download pool at [tierQuality]: known-oversize candidates
     * are dropped while any sane one remains, and an entirely oversize pool
     * yields its least-oversize member. Otherwise English-audio first (only
     * with [englishFirst] — dub pools are English by construction), torrent
     * streams first (they carry the P2P path the download manager prefers),
     * then best-seeded; addon ordering breaks ties.
     */
    private fun rankDownloadPool(
        pool: List<StreamItem>,
        tierQuality: String,
        englishFirst: Boolean,
    ): StreamItem? {
        if (pool.isEmpty()) return null
        val sane = pool.filterNot { AdaptiveStreamPicker.isKnownOversize(it, tierQuality) }
        if (sane.isEmpty()) return AdaptiveStreamPicker.pickLeastOversize(pool, tierQuality)
        return sane.sortedWith(
            compareBy<StreamItem> {
                if (englishFirst && !AdaptiveStreamPicker.hasEnglishAudio(it)) 1 else 0
            }
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
