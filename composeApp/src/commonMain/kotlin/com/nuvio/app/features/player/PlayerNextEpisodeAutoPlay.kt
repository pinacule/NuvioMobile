package com.nuvio.app.features.player

import com.nuvio.app.core.device.DataSaverRepository
import com.nuvio.app.core.device.DeviceEnvironment
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.CachedPreload
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.PreloadCache
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.AdaptiveStreamPicker
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun PlayerScreenRuntime.isAtNextEpisodeThreshold(): Boolean {
    if (playbackSnapshotKey != activePlaybackKey || playbackSnapshot.isLoading ||
        !initialSeekApplied || isScrubbingTimeline || errorMessage != null ||
        isShortPlaceholderDuration(playbackSnapshot.durationMs)
    ) return false
    // Preload: fetch next-episode sources ahead of time so a SkipNext tap
    // does not start its stream search cold.
    if (playerSettingsUiState.preloadNextEpisodeSources && !nextEpisodePreloadTriggered && nextEpisodeInfo != null) {
        val preloadLeadMs = playerSettingsUiState.streamAutoPlayTimeoutSeconds.toLong() * 1_000L
        val shouldPreloadByCard = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs + preloadLeadMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        val shouldPreloadByEnd = playbackSnapshot.durationMs > 0L &&
            (playbackSnapshot.durationMs - playbackSnapshot.positionMs) <= preloadLeadMs
        if (shouldPreloadByCard || shouldPreloadByEnd) {
            preloadNextEpisodeSources()
        }
    }
    return playbackSnapshot.isEnded || PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = playbackSnapshot.positionMs,
        durationMs = playbackSnapshot.durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
        thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
        thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    )
}

internal fun PlayerScreenRuntime.cancelNextEpisodeAutoPlay() {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    nextEpisodeAutoPlaySearching = false
}

/**
 * Binge group for the next-episode pick. The in-memory group wins; when it is
 * null (the current episode came from a download, the preload cache, or any
 * other non-binge launch) fall back to the group persisted for the series —
 * the same cache the initial-play path reads in StreamsRepository — but only
 * when both binge settings are on.
 */
internal fun resolveNextEpisodeBingeGroup(
    currentStreamBingeGroup: String?,
    settings: PlayerSettingsUiState,
    parentMetaId: String,
): String? {
    if (currentStreamBingeGroup != null) return currentStreamBingeGroup
    if (!settings.streamAutoPlayPreferBingeGroup || !settings.streamAutoPlayReuseBingeGroup) {
        return null
    }
    if (parentMetaId.isBlank()) return null
    return BingeGroupCacheRepository.get(parentMetaId)
}

internal fun CoroutineScope.launchPlayerNextEpisodeAutoPlay(
    previousJob: Job?,
    nextEpisodeInfo: NextEpisodeInfo?,
    allEpisodes: List<MetaVideo>,
    parentMetaId: String,
    parentMetaType: String,
    contentType: String?,
    settings: PlayerSettingsUiState,
    currentStreamBingeGroup: String?,
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onCachedPreloadSelected: (CachedPreload, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
): Job? {
    val nextVideoId = nextEpisodeInfo?.videoId ?: return null
    val nextVideo = allEpisodes.firstOrNull { video -> video.id == nextVideoId } ?: return null
    if (nextEpisodeInfo.hasAired != true) return null

    // A real, playable download wins over the silent preload cache. The cache
    // is only fetched when no download exists (checked at fetch start), so a
    // stale cached copy must never shadow a download that finished later.
    val downloadedNextEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextVideo.season,
        episodeNumber = nextVideo.episode,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    val cachedPreload = PreloadCache.lookup(nextVideo.id)
    if (cachedPreload != null) {
        onCachedPreloadSelected(cachedPreload, nextVideo)
        return null
    }

    previousJob?.cancel()
    onSearchingChanged(true)

    val type = contentType ?: parentMetaType
    val shouldAutoSelectInManualMode =
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (
                settings.streamAutoPlayNextEpisodeEnabled ||
                    settings.streamAutoPlayPreferBingeGroup
                )

    val bingeGroupOnlyManualMode =
        shouldAutoSelectInManualMode &&
            (!settings.streamAutoPlayNextEpisodeEnabled ||
                !settings.streamAutoPlayNextEpisodeFallbackEnabled) &&
            settings.streamAutoPlayPreferBingeGroup

    val effectiveMode = if (shouldAutoSelectInManualMode) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
    }
    val effectiveSource = if (shouldAutoSelectInManualMode) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        settings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedAddons
    }
    val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedPlugins
    }
    val effectiveRegex = if (shouldAutoSelectInManualMode) {
        ""
    } else {
        settings.streamAutoPlayRegex
    }
    val preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }

    // Unconfigured (MANUAL) mode uses the adaptive pick: best-seeded stream at the
    // quality for the current network/battery/data-saver state. Explicitly configured
    // modes (first stream / regex) keep their existing behavior.
    val useAdaptivePick = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL

    fun adaptiveTargetQuality(): String {
        DataSaverRepository.ensureLoaded()
        return AdaptiveStreamPicker.targetQuality(
            isCellular = DeviceEnvironment.isCellular(),
            batteryPercent = DeviceEnvironment.batteryPercent(),
            dataSaverActive = DataSaverRepository.isActiveNow(),
        )
    }

    return launch {
        // The searching flag was set before this job launched; it must be
        // cleared on EVERY exit path (normal, early return, cancellation,
        // or a throw from stream loading / resolving), otherwise
        // playNextEpisode() early-returns forever and Skip Next goes dead.
        // onSearchingChanged is playbackKey-guarded by the caller
        // (PlayerScreenRuntimeSourceActions.playNextEpisode), so invoking
        // it from finally cannot resurrect state for a superseded episode.
        try {
            PlayerStreamsRepository.loadEpisodeStreams(
                type = type,
                videoId = nextVideo.id,
                season = nextVideo.season,
                episode = nextVideo.episode,
            )

            if (effectiveMode == StreamAutoPlayMode.MANUAL) {
                onSearchingChanged(false)
                onManualSelectionRequired(nextVideo)
                return@launch
            }

            val installedAddonNames = AddonRepository.uiState.value.addons
                .enabledAddons()
                .map { it.displayTitle }
                .toSet()
            val debridSettings = DebridSettingsRepository.snapshot()

            val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var selectedStream: StreamItem? = null
            val autoSelectSettled = CompletableDeferred<Unit>()

            fun settleAutoSelect() {
                if (!autoSelectSettled.isCompleted) {
                    autoSelectSettled.complete(Unit)
                }
            }

            fun selectStream(stream: StreamItem) {
                autoSelectTriggered = true
                selectedStream = stream
                settleAutoSelect()
            }

            fun finishWithoutSelection() {
                autoSelectTriggered = true
                settleAutoSelect()
            }

            fun trySelectStream(streams: List<StreamItem>): StreamItem? =
                if (useAdaptivePick) {
                    val target = adaptiveTargetQuality()
                    // Keep the same release across episodes when possible: restrict the
                    // adaptive pick to the current binge group, falling back to all streams.
                    val bingeCandidates = preferredBingeGroup?.takeIf { it.isNotEmpty() }?.let { group ->
                        streams.filter { it.behaviorHints.bingeGroup == group }.takeIf { it.isNotEmpty() }
                    }
                    val preferEnglish = AdaptiveStreamPicker.shouldPreferEnglishAudio(
                        settings.preferredAudioLanguage,
                    )
                    // Binge-only manual mode (no fallback): with no stream from
                    // the current binge group there is nothing the user asked
                    // this mode to auto-pick. Returning null routes to
                    // onManualSelectionRequired (the picker) instead of
                    // silently picking any stream. The dub escape below still
                    // applies when a binge candidate set exists.
                    if (bingeGroupOnlyManualMode && bingeCandidates.isNullOrEmpty()) {
                        return null
                    }
                    // Dub over sub, always: if the binge group contains no English-dub
                    // stream, don't lock the next episode into the group's language —
                    // search all streams for a dub instead.
                    val candidates = if (preferEnglish && bingeCandidates != null &&
                        bingeCandidates.none { AdaptiveStreamPicker.hasEnglishAudio(it) }
                    ) {
                        streams
                    } else {
                        bingeCandidates ?: streams
                    }
                    AdaptiveStreamPicker.pickBestSeeded(
                        candidates,
                        target,
                        preferEnglishAudio = preferEnglish,
                        requireHdrSeeders = true,
                    )
                } else {
                    StreamAutoPlaySelector.selectAutoPlayStream(
                        streams = streams,
                        mode = effectiveMode,
                        regexPattern = effectiveRegex,
                        source = effectiveSource,
                        installedAddonNames = installedAddonNames,
                        selectedAddons = effectiveSelectedAddons,
                        selectedPlugins = effectiveSelectedPlugins,
                        preferredBingeGroup = preferredBingeGroup,
                        preferBingeGroupInSelection = settings.streamAutoPlayPreferBingeGroup,
                        bingeGroupOnly = bingeGroupOnlyManualMode,
                        debridEnabled = debridSettings.canResolvePlayableLinks,
                        activeResolverProviderId = debridSettings.activeResolverProviderId,
                        preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
                            settings.preferredAudioLanguage,
                        ),
                        requireHdrSeeders = true,
                    )
                }

            fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? {
                if (useAdaptivePick) return null
                if (preferredBingeGroup == null || !settings.streamAutoPlayPreferBingeGroup) return null
                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = streams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = preferredBingeGroup,
                    preferBingeGroupInSelection = true,
                    bingeGroupOnly = true,
                    debridEnabled = debridSettings.canResolvePlayableLinks,
                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                    preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
                        settings.preferredAudioLanguage,
                    ),
                    requireHdrSeeders = true,
                )
            }

            val innerJob = launch {
                PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
                    if (state.groups.isEmpty() && state.isAnyLoading) return@collectLatest

                    val allStreams = state.groups.flatMap { it.streams }

                    if (autoSelectTriggered) {
                        // Already resolved.
                    } else if (timeoutElapsed) {
                        if (allStreams.isNotEmpty()) {
                            val candidate = trySelectStream(allStreams)
                            if (candidate != null) {
                                selectStream(candidate)
                            }
                        }
                    } else if (allStreams.isNotEmpty()) {
                        val earlyMatch = tryBingeGroupOnly(allStreams)
                        if (earlyMatch != null) {
                            selectStream(earlyMatch)
                        }
                    }

                    if (!autoSelectTriggered && !state.isAnyLoading) {
                        if (allStreams.isNotEmpty()) {
                            val candidate = trySelectStream(allStreams)
                            if (candidate != null) {
                                selectStream(candidate)
                            }
                        }
                        if (!autoSelectTriggered) {
                            finishWithoutSelection()
                        }
                        return@collectLatest
                    }

                    if (autoSelectTriggered) return@collectLatest
                }
            }

            val timeoutMs = timeoutSeconds * 1_000L
            val isBoundedTimeout = timeoutSeconds in 1..30

            if (isBoundedTimeout) {
                // If streams are already cached (preload), autoSelectSettled
                // completes immediately. Don't wait the full timeout in that case.
                val settled = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { autoSelectSettled.await() }
                if (settled == null) {
                    timeoutElapsed = true
                    if (!autoSelectTriggered) {
                        val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                        if (allStreams.isNotEmpty()) {
                            val candidate = trySelectStream(allStreams)
                            if (candidate != null) {
                                selectStream(candidate)
                            }
                        }
                    }
                }
                innerJob.cancel()
                if (selectedStream == null && !autoSelectTriggered) {
                    finishWithoutSelection()
                }
            } else {
                timeoutElapsed = true
                if (!autoSelectTriggered) {
                    val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                    if (allStreams.isNotEmpty()) {
                        trySelectStream(allStreams)?.let(::selectStream)
                    }
                }
                val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
                innerJob.cancel()
                if (completed == null && !autoSelectTriggered) {
                    val allStreams = PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams }
                    if (allStreams.isNotEmpty()) {
                        selectedStream = trySelectStream(allStreams)
                    }
                    finishWithoutSelection()
                }
            }

            val selected = selectedStream?.let { stream ->
                when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, nextVideo.season, nextVideo.episode)) {
                    is DirectDebridPlayableResult.Success -> result.stream
                    else -> {
                        result.toastMessage()?.let { NuvioToastController.show(it) }
                        PlayerStreamsRepository.loadEpisodeStreams(
                            type = type,
                            videoId = nextVideo.id,
                            season = nextVideo.season,
                            episode = nextVideo.episode,
                            forceRefresh = true,
                        )
                        null
                    }
                }
            }
            onSearchingChanged(false)
            if (selected != null) {
                onEpisodeStreamSelected(selected, nextVideo)
            } else {
                onManualSelectionRequired(nextVideo)
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: clear the flag in finally,
            // then let the parent scope's cancellation proceed.
            throw e
        } finally {
            onSearchingChanged(false)
        }
    }
}

internal fun PlayerScreenRuntime.preloadNextEpisodeSources() {
    if (nextEpisodePreloadTriggered) return
    val nextEp = nextEpisodeInfo ?: return
    if (nextEp.hasAired != true) return
    val type = contentType ?: return

    nextEpisodePreloadTriggered = true
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = scope.launch {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextEp.videoId,
            season = nextEp.season,
            episode = nextEp.episode
        )
    }
}

internal fun PlayerScreenRuntime.cancelNextEpisodePreload() {
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = null
    nextEpisodePreloadTriggered = false
}
