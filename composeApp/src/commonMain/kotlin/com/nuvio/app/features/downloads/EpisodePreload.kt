package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.device.DataSaverRepository
import com.nuvio.app.core.device.DeviceEnvironment
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.AdaptiveStreamPicker
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gating for the background next-episode preload (item 11): 60% watched,
 * WiFi only, battery above 25% (unknown battery is allowed), never on cellular.
 */
object EpisodePreloadPolicy {
    const val TRIGGER_FRACTION = 0.6f
    const val MIN_BATTERY_PERCENT = 25

    fun shouldTriggerPreload(
        progressFraction: Float,
        isWifi: Boolean,
        isCellular: Boolean,
        batteryPercent: Int,
        alreadyTriggeredForVideo: Boolean,
        isSeries: Boolean,
    ): Boolean {
        if (alreadyTriggeredForVideo || !isSeries) return false
        if (progressFraction < TRIGGER_FRACTION) return false
        if (!isWifi || isCellular) return false
        if (batteryPercent != -1 && batteryPercent <= MIN_BATTERY_PERCENT) return false
        return true
    }
}

/** One-shot silent preload of the next episode at the adaptive quality. */
internal object EpisodePreloader {
    suspend fun preloadNextEpisode(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        videos: List<MetaVideo>,
        seasonNumber: Int,
        episodeNumber: Int,
        preferredAddonId: String?,
        targetQuality: String,
        /** Currently playing video id: its cache entry is never evicted. */
        currentVideoId: String?,
    ) {
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, seasonNumber, episodeNumber)
            ?.takeIf { it.available }
            ?: return
        val nextSeason = next.season ?: return
        val nextEpisode = next.episode ?: return
        // Already silently cached (and still on disk).
        if (PreloadCache.lookup(next.id) != null) return
        // Already downloaded for real: the Downloads path owns playback.
        DownloadsRepository.ensureLoaded()
        if (DownloadsRepository.hasActiveOrCompletedDownload(parentMetaId, nextSeason, nextEpisode)) return
        val groups = EpisodeDownloadPlanner.fetchAddonStreams(contentType, next.id, preferredAddonId)
        val allStreams = groups.flatMap { it.streams }
        val quality = EpisodeDownloadPlanner.closestQuality(
            EpisodeDownloadPlanner.distinctQualities(allStreams),
            targetQuality,
        ) ?: return
        // Dub over sub, always: prefer an English-audio release at the chosen quality.
        runCatching { PlayerSettingsRepository.ensureLoaded() }
        val preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
            PlayerSettingsRepository.uiState.value.preferredAudioLanguage,
        )
        val template = EpisodeDownloadPlanner.pickBestStreamAtQuality(
            allStreams,
            quality,
            preferEnglishAudio = preferEnglishAudio,
        ) ?: return
        val request = buildPreloadRequest(
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            video = next,
            stream = template,
            quality = quality,
            currentVideoId = currentVideoId,
        ) ?: return
        PreloadCacheFetcher.start(request)
        EpisodePreloadWatcher.ensureWatching()
    }

    /**
     * Builds the synthetic [DownloadItem] for the cache fetch. It is never
     * registered in [DownloadsRepository]: nothing shows in the Downloads UI.
     * Null when the stream cannot be fetched as a file.
     */
    private fun buildPreloadRequest(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        video: MetaVideo,
        stream: StreamItem,
        quality: String,
        currentVideoId: String?,
    ): PreloadFetchRequest? {
        val directUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val torrentSource = if (directUrl == null) stream.torrentDownloadSource() else null
        if (torrentSource != null) {
            if (!AppFeaturePolicy.p2pDownloadsEnabled) return null
            P2pSettingsRepository.ensureLoaded()
            if (!P2pSettingsRepository.uiState.value.p2pEnabled) return null
        } else if (directUrl == null) {
            return null
        } else if (!directUrl.isSupportedDownloadUrl()) {
            return null
        }
        val sourceUrl = directUrl ?: torrentSource!!.magnetUri
        val extension = if (torrentSource != null) {
            torrentSource.filenameHint?.mediaFileExtensionOrNull() ?: TORRENT_FALLBACK_EXTENSION
        } else {
            sourceUrl.fileExtensionFromUrl()
        }
        // No path separators: platform transfers require a bare file name.
        val fileName = "preload_${video.id.sanitizeFileName()}.$extension"
        val now = DownloadsClock.nowEpochMs()
        val item = DownloadItem(
            id = "preload-$now-${video.id.sanitizeFileName()}",
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = video.id,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            episodeTitle = video.title,
            episodeThumbnail = video.thumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            downloadQuality = quality,
            sourceUrl = sourceUrl,
            sourceKind = if (torrentSource != null) DownloadSourceKind.Torrent else DownloadSourceKind.Http,
            torrentInfoHash = torrentSource?.infoHash,
            torrentFileIdx = torrentSource?.fileIdx,
            torrentTrackers = torrentSource?.trackers.orEmpty(),
            torrentFilenameHint = torrentSource?.filenameHint,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            subtitleRequests = addonSubtitleRequests(contentType, video.id),
            sourceSubtitles = stream.externalSubtitles,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        return PreloadFetchRequest(
            item = item,
            fileName = fileName,
            videoId = video.id,
            parentMetaId = parentMetaId,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            quality = quality,
            exceptVideoId = currentVideoId,
        )
    }
}

/**
 * Pauses the active preload fetch when the network turns cellular and resumes
 * it when WiFi returns. Stops itself once no fetch is active or paused.
 *
 * NOTE: calls DeviceEnvironment, which is provided by the playback worker's
 * expect/actual (see batch-11 spec); this file only calls it.
 */
internal object EpisodePreloadWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    fun ensureWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(5_000)
                val cellular = runCatching { DeviceEnvironment.isCellular() }.getOrDefault(false)
                val wifi = runCatching { DeviceEnvironment.isWifi() }.getOrDefault(false)
                if (cellular) {
                    PreloadCacheFetcher.pause()
                } else if (wifi) {
                    PreloadCacheFetcher.resume()
                }
                if (!PreloadCacheFetcher.hasActiveFetch && !PreloadCacheFetcher.hasPausedFetch) break
            }
            watchJob = null
        }
    }
}

/**
 * Called from [persistPlaybackProgressTick]; triggers at most once per episode.
 * DeviceEnvironment is provided by the playback worker (expect/actual).
 */
internal fun PlayerScreenRuntime.maybePreloadNextEpisodeDownload() {
    if (!isSeries) return
    val season = activeSeasonNumber ?: return
    val episode = activeEpisodeNumber ?: return
    val videoKey = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    if (nextEpisodeDownloadPreloadVideoId == videoKey) return
    val durationMs = playbackSnapshot.durationMs
    if (durationMs <= 0L) return
    val fraction = playbackSnapshot.positionMs.toFloat() / durationMs.toFloat()
    val wifi = runCatching { DeviceEnvironment.isWifi() }.getOrDefault(false)
    val cellular = runCatching { DeviceEnvironment.isCellular() }.getOrDefault(false)
    val battery = runCatching { DeviceEnvironment.batteryPercent() }.getOrDefault(-1)
    if (!EpisodePreloadPolicy.shouldTriggerPreload(
            progressFraction = fraction,
            isWifi = wifi,
            isCellular = cellular,
            batteryPercent = battery,
            alreadyTriggeredForVideo = false,
            isSeries = true,
        )
    ) return
    nextEpisodeDownloadPreloadVideoId = videoKey
    val dataSaverActive = runCatching { DataSaverRepository.isActiveNow() }.getOrDefault(false)
    val targetQuality = AdaptiveStreamPicker.targetQuality(
        isCellular = cellular,
        batteryPercent = battery,
        dataSaverActive = dataSaverActive,
    )
    scope.launch {
        runCatching {
            EpisodePreloader.preloadNextEpisode(
                contentType = contentType ?: parentMetaType,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                videos = playerMetaVideos,
                seasonNumber = season,
                episodeNumber = episode,
                preferredAddonId = activeProviderAddonId,
                targetQuality = targetQuality,
                currentVideoId = videoKey,
            )
        }
    }
}

/**
 * Called from [persistPlaybackProgressTick]; marks the silent cache for the
 * current episode as eligible for eviction once it counts as watched (>= 80%,
 * same threshold as the scrobble outbox). The file is NOT deleted here — it is
 * deleted when the episode is left/replaced (see [evictEligibleExcept]), never
 * while it is still playing, so seeking back in the last 20% keeps working.
 */
internal fun PlayerScreenRuntime.maybeMarkWatchedPreloadCacheEligible() {
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    val durationMs = playbackSnapshot.durationMs
    if (durationMs <= 0L) return
    val fraction = playbackSnapshot.positionMs.toFloat() / durationMs.toFloat()
    if (fraction >= 0.8f) {
        scope.launch { PreloadCache.markWatchedEligible(videoId) }
    }
}
