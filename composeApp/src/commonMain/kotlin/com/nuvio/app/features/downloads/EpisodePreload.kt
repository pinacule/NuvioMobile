package com.nuvio.app.features.downloads

import com.nuvio.app.core.device.DeviceEnvironment
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
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

/** One-shot background download of the next episode at the default quality. */
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
    ) {
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, seasonNumber, episodeNumber)
            ?.takeIf { it.available }
            ?: return
        val nextSeason = next.season ?: return
        val nextEpisode = next.episode ?: return
        DownloadsRepository.ensureLoaded()
        if (DownloadsRepository.hasActiveOrCompletedDownload(parentMetaId, nextSeason, nextEpisode)) return
        val groups = EpisodeDownloadPlanner.fetchAddonStreams(contentType, next.id, preferredAddonId)
        val allStreams = groups.flatMap { it.streams }
        val quality = EpisodeDownloadPlanner.closestQuality(
            EpisodeDownloadPlanner.distinctQualities(allStreams),
            EpisodeDownloadPlanner.DEFAULT_QUALITY,
        ) ?: return
        val template = EpisodeDownloadPlanner.pickBestStreamAtQuality(allStreams, quality) ?: return
        val manifest = groups.firstOrNull { it.addonId == template.addonId }?.manifest ?: return
        val result = SeasonDownloads.enqueueVideos(
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            targets = listOf(next),
            addon = manifest,
            template = template,
            templateVideoId = next.id,
        )
        if (result is SeasonDownloadResult.Queued && result.queued > 0) {
            EpisodePreloadWatcher.ensureWatching()
        }
    }
}

/**
 * Pauses active preload downloads when the network turns cellular and resumes
 * them when WiFi returns. Stops itself once no preload download is active.
 *
 * NOTE: calls DeviceEnvironment, which is provided by the playback worker's
 * expect/actual (see batch-11 spec); this file only calls it.
 */
internal object EpisodePreloadWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private val pausedByWatcher = mutableSetOf<String>()

    fun ensureWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(5_000)
                DownloadsRepository.ensureLoaded()
                val active = DownloadsRepository.uiState.value.items
                    .filter { it.isPreload && (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Paused) }
                if (active.isEmpty()) break
                val cellular = runCatching { DeviceEnvironment.isCellular() }.getOrDefault(false)
                val wifi = runCatching { DeviceEnvironment.isWifi() }.getOrDefault(false)
                if (cellular) {
                    active.filter { it.status == DownloadStatus.Downloading }.forEach {
                        DownloadsRepository.pauseDownload(it.id)
                        pausedByWatcher.add(it.id)
                    }
                } else if (wifi) {
                    pausedByWatcher.toList().forEach { id ->
                        val item = DownloadsRepository.uiState.value.items.firstOrNull { it.id == id }
                        if (item != null && item.isPreload && item.status == DownloadStatus.Paused) {
                            DownloadsRepository.resumeDownload(id)
                        } else {
                            pausedByWatcher.remove(id)
                        }
                    }
                }
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
            )
        }
    }
}
