package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.device.DeviceEnvironment
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.buildP2pMagnetUri
import com.nuvio.app.features.p2p.canonicalP2pInfoHash
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/** Matches "HTTP 403" / "HTTP 404" / "HTTP 410" inside a plain-text failure message. */
private val staleHttpCodeInMessage =
    Regex("""HTTP\s+(${STALE_HTTP_STATUS_CODES.joinToString("|")})\b""")

/**
 * True when a plain-text failure message describes a transient torrent condition
 * worth an automatic retry: a stream-preparation collision, or a stale loopback
 * link — the engine expired the stream mid-download, so a retry prepares a fresh
 * one. Uses the same stale-code detection as the repository's
 * humanizeDownloadFailureMessage "link expired" mapping.
 */
internal fun isTransientTorrentFailureMessage(message: String): Boolean =
    message.contains("stream preparation", ignoreCase = true) ||
        staleHttpCodeInMessage.containsMatchIn(message)

object DownloadsRepository {
    private val log = Logger.withTag("DownloadsRepository")

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    /**
     * Transient-failure retry budget per download id: consecutive retries without
     * progress (see [DownloadRecoveryBudget]), so a long torrent that keeps
     * advancing between transient failures is never failed for their total count.
     */
    private val torrentPrepRetryBudgets = mutableMapOf<String, DownloadRecoveryBudget>()

    /** How many times IN A ROW, without progress, a torrent download retries a transient failure. */
    private const val MAX_TORRENT_PREP_RETRIES = 4
    /** Base backoff between transient-failure retries; multiplied by the attempt number. */
    private const val TORRENT_PREP_RETRY_BASE_DELAY_MS = 5_000L

    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L
    /** Background source-URL refreshes for manual retries; never blocks the UI. */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Finished downloads kept on disk while their replacement is in flight, keyed by the
     * replacement's download id. The old file is deleted only after the replacement succeeds;
     * on failure (or cancel) the stashed snapshot is restored so the episode stays playable.
     * Write-through persisted by [persist] (see [DownloadsCodec]) so the stash survives
     * process death; [loadFromDisk] settles any stash whose replacement already reached a
     * terminal state while the process was gone (see [settlePersistedStashes]).
     */
    private val stashedReplacements = mutableMapOf<String, StashedReplacement>()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        torrentPrepRetryBudgets.clear()
        stashedReplacements.clear()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    /**
     * Downloads verified playable from disk right now: status Completed AND
     * the local file still exists (the same check playback lookups use).
     * The offline homepage consumes only these — a Completed record whose
     * file was deleted outside the app is not downloaded content anymore.
     */
    fun playableLocalDownloads(): List<DownloadItem> {
        ensureLoaded()
        return _uiState.value.items.filter { it.hasPlayableLocalFile() }
    }

    /** True when the episode is already downloading, paused or downloaded (failed ones may be retried). */
    fun hasActiveOrCompletedDownload(parentMetaId: String, seasonNumber: Int?, episodeNumber: Int?): Boolean {
        ensureLoaded()
        val key = buildLogicalKey(parentMetaId, seasonNumber, episodeNumber)
        return _uiState.value.items.any { it.logicalContentKey == key && it.status != DownloadStatus.Failed }
    }

    /**
     * True only when the episode's download has finished (status Completed).
     * Downloading, paused and failed downloads all count as NOT finished —
     * used by the "download next N" window, which must keep an unfinished
     * current episode in the selection.
     */
    fun hasCompletedDownload(parentMetaId: String, seasonNumber: Int?, episodeNumber: Int?): Boolean {
        ensureLoaded()
        val key = buildLogicalKey(parentMetaId, seasonNumber, episodeNumber)
        return _uiState.value.items.any { it.logicalContentKey == key && it.status == DownloadStatus.Completed }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        downloadQuality: String? = null,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val directUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val torrentSource = if (directUrl == null) stream.torrentDownloadSource() else null
        if (torrentSource != null) {
            if (!AppFeaturePolicy.p2pDownloadsEnabled) return DownloadEnqueueResult.MissingUrl
            P2pSettingsRepository.ensureLoaded()
            if (!P2pSettingsRepository.uiState.value.p2pEnabled) return DownloadEnqueueResult.P2pDisabled
        } else if (directUrl == null) {
            return DownloadEnqueueResult.MissingUrl
        } else if (!directUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        val sourceUrl = directUrl ?: requireNotNull(torrentSource).magnetUri

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        var pendingStash: StashedReplacement? = null
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            // If the replaced item is itself an in-flight replacement, its stash moves to the
            // new download so the original finished file is still settled either way.
            val carriedStash = stashedReplacements.remove(existing.id)
            if (existing.status == DownloadStatus.Completed) {
                // Delete-late: keep the finished file on disk until the replacement proves
                // good. Safe to defer because buildFileName includes the downloadId, so the
                // new download writes to a different file.
                val oldFileUri = playableLocalFileUri(existing) ?: existing.localFileUri
                val normalizedExisting =
                    _uiState.value.items.firstOrNull { it.id == existing.id } ?: existing
                pendingStash = StashedReplacement(item = normalizedExisting, fileUri = oldFileUri)
            } else {
                DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
                DownloadsPlatformDownloader.removePartialFile(existing.fileName)
                pendingStash = carriedStash
            }
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        pendingStash?.let { stashedReplacements[downloadId] = it }
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            extension = if (torrentSource != null) {
                torrentSource.filenameHint?.mediaFileExtensionOrNull() ?: TORRENT_FALLBACK_EXTENSION
            } else {
                sourceUrl.fileExtensionFromUrl()
            },
            downloadId = downloadId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            downloadQuality = downloadQuality?.trim()?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceKind = if (torrentSource != null) DownloadSourceKind.Torrent else DownloadSourceKind.Http,
            torrentInfoHash = torrentSource?.infoHash,
            torrentFileIdx = torrentSource?.fileIdx,
            torrentTrackers = torrentSource?.trackers.orEmpty(),
            torrentFilenameHint = torrentSource?.filenameHint,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(contentType, videoId),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                waitingForWifi = false,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    /**
     * Wi-Fi snapshot for the download gate ([DownloadWifiGate]): fail-closed,
     * so an unknown transport never transfers. Desktop/iOS report Wi-Fi
     * unconditionally, so the gate only ever bites on Android.
     */
    private fun isWifiAvailable(): Boolean =
        runCatching { DeviceEnvironment.isWifi() }.getOrDefault(false)

    /**
     * Parks every actively transferring download as waiting for Wi-Fi
     * (called by the Android network watcher when Wi-Fi is lost). Progress
     * is preserved; this is not an error and never touches Failed items.
     * A user-paused download is already Paused, so it is never flagged and
     * never auto-resumed.
     */
    fun pauseDownloadsForWifi() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach { downloadId ->
                activeHandles.remove(downloadId)?.cancel()
                mutateItem(downloadId) { current ->
                    if (current.status == DownloadStatus.Downloading) {
                        DownloadWifiGate.parkAsWaiting(current)
                    } else {
                        current
                    }
                }
            }
    }

    /**
     * Resumes every download parked by the Wi-Fi gate, now that Wi-Fi is
     * back. User-paused downloads (flag unset) are left alone. Re-checks
     * Wi-Fi per item through [startDownload]'s own guard, so a race with
     * another network change cannot start a transfer on cellular.
     */
    fun resumeWifiWaitingDownloads() {
        ensureLoaded()
        if (!isWifiAvailable()) return
        _uiState.value.items
            .filter { DownloadWifiGate.shouldAutoResume(it, isWifi = true) }
            .map { it.id }
            .forEach { downloadId ->
                val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return@forEach
                val reset = item.copy(
                    status = DownloadStatus.Downloading,
                    waitingForWifi = false,
                    errorMessage = null,
                    localFileUri = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
                replaceItem(reset)
                persist()
                startDownload(reset)
            }
    }

    /**
     * Marks one download as waiting for Wi-Fi without starting it (used by
     * the notification Resume action when it is tapped while off Wi-Fi).
     * A no-op for finished downloads; anything else is parked, so a resume
     * tap off Wi-Fi always lands in the waiting state, never on cellular.
     */
    fun markWaitingForWifi(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status == DownloadStatus.Completed) return
        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            if (current.status == DownloadStatus.Completed) current
            else DownloadWifiGate.parkAsWaiting(current)
        }
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        if (!isWifiAvailable()) {
            // Never transfer on cellular, even on an explicit resume tap:
            // park as waiting instead. The watcher auto-resumes on Wi-Fi.
            mutateItem(downloadId) { current ->
                if (current.status == DownloadStatus.Paused || current.status == DownloadStatus.Failed) {
                    DownloadWifiGate.parkAsWaiting(current)
                } else {
                    current
                }
            }
            return
        }

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            waitingForWifi = false,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    /**
     * Swaps in a freshly resolved source URL for [item] (called by the platform
     * scheduler after an automatic stale-link refresh). The store owns the
     * transfer, but pause/resume and the UI read from here, so both copies
     * must agree on the URL.
     */
    internal fun refreshItemSource(item: DownloadItem) {
        ensureLoaded()
        replaceItem(item)
        persist()
    }

    fun retryDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Failed || item.sourceKind != DownloadSourceKind.Http) {
            resumeDownload(downloadId)
            return
        }
        // A failed HTTP download almost always died on a stale link: flip to
        // Downloading immediately for UI feedback, then swap in a freshly
        // resolved URL before the transfer starts so the dead link is never
        // retried verbatim. Falls back to the old URL when nothing fresh
        // resolves. Where the platform verifies a resumed file's size, the
        // partial (and its progress) is kept; elsewhere the retry starts over.
        val keepPartial = DownloadsPlatformDownloader.keepsPartialAcrossSourceSwap
        val marked = item.copy(
            status = DownloadStatus.Downloading,
            waitingForWifi = false,
            errorMessage = null,
            localFileUri = null,
            downloadedBytes = if (keepPartial) item.downloadedBytes else 0L,
            totalBytes = if (keepPartial) item.totalBytes else null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )
        replaceItem(marked)
        persist()
        refreshScope.launch {
            val refreshed = try {
                refreshHttpDownloadSource(marked)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (refreshError: Exception) {
                null
            }?.takeIf { it.sourceUrl != marked.sourceUrl }
            // The user may have paused or cancelled while the fresh URL resolved.
            val current = _uiState.value.items.firstOrNull { it.id == downloadId }
            if (current?.status != DownloadStatus.Downloading) return@launch
            if (refreshed != null) {
                // The new link may point at a different encode: without a size
                // check on resume, drop the partial so bytes are never resumed
                // across files. With one, keep it — the transfer restarts from
                // zero by itself if the fresh link's size differs.
                if (!keepPartial) DownloadsPlatformDownloader.deletePartialFileNow(marked.fileName)
                val toStart = refreshed.copy(
                    status = DownloadStatus.Downloading,
                    downloadedBytes = marked.downloadedBytes,
                    totalBytes = marked.totalBytes,
                    errorMessage = null,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
                replaceItem(toStart)
                persist()
                // Fresh URL: never send the dead link's ETag validator against it.
                startDownload(toStart, resetValidator = true)
            } else {
                startDownload(marked)
            }
        }
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.cancel()
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        val stashed = stashedReplacements.remove(downloadId)
        if (stashed != null) {
            // Cancelling a replacement: give back the superseded finished episode.
            DownloadsPlatformDownloader.removePartialFile(item.fileName)
            val restored = _uiState.value.items.toMutableList()
            restored.removeAll { it.id == downloadId }
            restored.add(0, stashed.item)
            publish(restored)
            persist()
        } else {
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName)

            publish(_uiState.value.items.filterNot { it.id == downloadId })
            persist()
        }
    }

    private fun loadFromDisk() {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            stashedReplacements.clear()
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val decoded = decodePersistedPayload(payload)
        val restoredItems = decoded.items
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)
                // The Wi-Fi-park flag can live in either record: an
                // app-process park writes the repository payload, a
                // background-process park writes the transfer store. A
                // Paused restore keeps the flag from both, so a parked
                // download still auto-resumes after process death; a
                // running restore clears it, since a transfer that is
                // actually running is not waiting.
                val wifiMerged = if (statusNormalized.status == DownloadStatus.Paused) {
                    statusNormalized.copy(
                        waitingForWifi = item.waitingForWifi || statusNormalized.waitingForWifi,
                    )
                } else {
                    statusNormalized.copy(waitingForWifi = false)
                }

                val localUriNormalized = normalizeCompletedLocalFileUri(wifiMerged)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        // The process may have died while a replacement was in flight: settle its
        // persisted stash against the state the replacement was restored in. A
        // still-active (or paused) replacement keeps its stash in memory, and the
        // live callbacks below settle it exactly as before.
        val settlement = settlePersistedStashes(restoredItems, decoded.stashedReplacements)
        stashedReplacements.clear()
        stashedReplacements.putAll(settlement.remainingStashes)
        settlement.completedStashes.forEach { stash -> deleteStashedReplacementFile(stash) }
        settlement.restoredReplacementFileNames.forEach { fileName ->
            DownloadsPlatformDownloader.removePartialFile(fileName)
        }
        if (settlement.remainingStashes.size != decoded.stashedReplacements.size) {
            shouldPersistNormalized = true
        }
        val normalized = settlement.items

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        normalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .forEach(::startDownload)
    }

    /** True when the item is still an in-flight download the callbacks should settle. */
    private fun isSettleableDownload(downloadId: String): Boolean =
        _uiState.value.items.firstOrNull { it.id == downloadId }?.status == DownloadStatus.Downloading

    /**
     * Humanizes a terminal download failure message that arrived as a plain string:
     * never park a raw engine message or a bare HTTP code on the download. Mirrors
     * AndroidDownloadScheduler.humanDownloadError's mapping (which works from the
     * exception type); this string-based version covers the paths that only carry
     * the message text.
     */
    private fun humanizeDownloadFailureMessage(message: String): String = runBlocking {
        when {
            message.contains("stream preparation", ignoreCase = true) ->
                getString(Res.string.download_failed)
            staleHttpCodeInMessage.containsMatchIn(message) ->
                getString(Res.string.download_failed_link_expired)
            else -> message.ifBlank { getString(Res.string.download_failed) }
        }
    }

    /**
     * When a torrent download failed on a transient engine condition — a
     * stream-preparation collision (e.g. "stream preparation already pending")
     * or a stale loopback link (HTTP 403/404/410 in the message) — and still has
     * retry budget left, consumes one retry and returns its attempt number in the
     * current no-progress streak; null when the failure is terminal.
     */
    private fun consumeTorrentPreparationRetry(item: DownloadItem, message: String): Int? {
        if (!item.isTorrent) return null
        if (!isTransientTorrentFailureMessage(message)) return null
        val downloadedBytes = _uiState.value.items.firstOrNull { it.id == item.id }?.downloadedBytes ?: 0L
        val budget = torrentPrepRetryBudgets.getOrElse(item.id) { DownloadRecoveryBudget() }
            .consume(downloadedBytes, MAX_TORRENT_PREP_RETRIES)
            ?: return null
        torrentPrepRetryBudgets[item.id] = budget
        return budget.consecutiveFailures
    }

    /**
     * Backs off and restarts a torrent download after a transient failure
     * (preparation collision or stale link). The item stays Downloading; the
     * humanized terminal message is only parked once the retry budget is exhausted.
     */
    private fun scheduleTorrentPreparationRetry(item: DownloadItem, attempt: Int) {
        refreshScope.launch {
            delay(TORRENT_PREP_RETRY_BASE_DELAY_MS * attempt)
            val current = _uiState.value.items.firstOrNull { it.id == item.id }
            // The user may have paused, cancelled or deleted the download while
            // we were backing off: only restart if it's still Downloading.
            if (current?.status == DownloadStatus.Downloading) {
                startDownload(current)
            } else {
                torrentPrepRetryBudgets.remove(item.id)
            }
        }
    }

    private fun startDownload(item: DownloadItem, resetValidator: Boolean = false) {
        if (!isWifiAvailable()) {
            // Wi-Fi-only gate: every transfer start funnels through here
            // (enqueue, resume, retry, restart after process death), so a
            // single guard covers all of them. Park the incoming item —
            // which may carry a freshly resolved URL — instead of starting
            // the transfer, so no byte ever moves on cellular.
            replaceItem(DownloadWifiGate.parkAsWaiting(item))
            persist()
            return
        }

        val request = DownloadPlatformRequest(item, resetValidator)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                torrentPrepRetryBudgets.remove(item.id)
                if (isSettleableDownload(item.id)) {
                    // The replacement proved good: delete the superseded finished file,
                    // including its subtitle sidecar / partial storage.
                    stashedReplacements.remove(item.id)?.let { stash -> deleteStashedReplacementFile(stash) }
                }
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                // Transient torrent failures (engine preparation collisions like
                // "stream preparation already pending", or a stale loopback link):
                // back off and retry instead of parking a terminal failure,
                // with a budget of 4 retries in a row without progress.
                val retryAttempt = consumeTorrentPreparationRetry(item, message)
                if (retryAttempt != null) {
                    scheduleTorrentPreparationRetry(item, retryAttempt)
                } else {
                    torrentPrepRetryBudgets.remove(item.id)
                    val stashed = if (isSettleableDownload(item.id)) {
                        stashedReplacements.remove(item.id)
                    } else {
                        null
                    }
                    if (stashed != null) {
                        // The replacement failed: restore the superseded finished episode and
                        // drop the failed attempt entirely.
                        DownloadsPlatformDownloader.removePartialFile(item.fileName)
                        val restored = _uiState.value.items.toMutableList()
                        restored.removeAll { it.id == item.id }
                        restored.add(0, stashed.item)
                        publish(restored)
                        persist()
                    } else {
                        mutateItem(item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) {
                                current
                            } else {
                                current.copy(
                                    status = DownloadStatus.Failed,
                                    errorMessage = humanizeDownloadFailureMessage(message),
                                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                )
                            }
                        }
                    }
                }
            },
            onPaused = {
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    /**
     * Deletes a settled stash's superseded finished file together with its
     * `<file>.subtitles/` sidecar and any partial storage — the same cleanup the
     * manual-delete path ([cancelDownload]) performs via removeFile +
     * removePartialFile (the platform removePartialFile also drops the sidecar on
     * Android/iOS; the explicit [DownloadSubtitleStorage] removal covers desktop,
     * mirroring PreloadCache's deleteEntry).
     */
    private fun deleteStashedReplacementFile(stashed: StashedReplacement) {
        stashed.fileUri?.let { fileUri ->
            DownloadsPlatformDownloader.removeFile(fileUri)
            runCatching { DownloadSubtitleStorage(fileUri).remove() }
        }
        DownloadsPlatformDownloader.removePartialFile(stashed.item.fileName)
    }

    private fun persist() {
        val payload = DownloadsCodec.encodeItems(
            items = _uiState.value.items,
            stashedReplacements = stashedReplacements.toMap(),
        )
        DownloadsStorage.savePayload(payload)
        // Last-good backup for decodePersistedPayload: written after the
        // primary, so a crash mid-persist leaves the previous good payload
        // in the backup rather than a torn one in both.
        DownloadsStorage.saveBackupPayload(payload)
    }

    /**
     * Decodes the persisted payload, recovering from the last-good backup
     * when the primary copy is corrupt. Without this, one truncated payload
     * silently decoded to an empty payload and the next [persist] made the
     * loss permanent — the files stayed on disk but orphaned from the UI.
     */
    private fun decodePersistedPayload(payload: String): StoredDownloadsPayload {
        DownloadsCodec.decodeOrNull(payload)?.let { return it }
        val backup = DownloadsStorage.loadBackupPayload()?.trim().orEmpty()
        val recovered = backup.takeIf { it.isNotEmpty() }
            ?.let { DownloadsCodec.decodeOrNull(it) }
        if (recovered != null) {
            log.w { "Downloads payload failed to decode; restored the last-good backup" }
            DownloadsStorage.savePayload(backup)
            return recovered
        }
        log.e { "Downloads payload failed to decode and no valid backup exists; starting empty" }
        return StoredDownloadsPayload()
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

/**
 * A finished download kept on disk while its replacement is in flight: the
 * superseded item snapshot plus the file it lives in. Persisted in
 * [StoredDownloadsPayload] keyed by the replacement's download id, so the
 * original survives process death until the replacement settles.
 */
@Serializable
internal data class StashedReplacement(
    val item: DownloadItem,
    val fileUri: String?,
)

@Serializable
internal data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    /** Stashes awaiting settlement, keyed by the replacement's download id. Absent in pre-fix payloads. */
    val stashedReplacements: Map<String, StashedReplacement> = emptyMap(),
)

internal object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(payload: String): StoredDownloadsPayload =
        decodeOrNull(payload) ?: StoredDownloadsPayload()

    /** Strict decode: null when [payload] is not a valid stored payload. */
    fun decodeOrNull(payload: String): StoredDownloadsPayload? =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload)
        }.getOrNull()

    fun decodeItems(payload: String): List<DownloadItem> = decode(payload).items

    fun encodeItems(
        items: Collection<DownloadItem>,
        stashedReplacements: Map<String, StashedReplacement> = emptyMap(),
    ): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
                stashedReplacements = stashedReplacements,
            ),
        )
}

/** Outcome of settling persisted stashes against the items loaded from disk. */
internal data class StashSettlement(
    /** The item list to publish: settled replacements removed, restored stashes back in. */
    val items: List<DownloadItem>,
    /** Stashes whose replacement is still in flight (or paused): kept for the live callbacks. */
    val remainingStashes: Map<String, StashedReplacement>,
    /** Stashes whose replacement completed: their superseded files are safe to delete. */
    val completedStashes: List<StashedReplacement>,
    /** File names of failed replacements dropped in favour of their stash: their partials go too. */
    val restoredReplacementFileNames: List<String>,
)

/**
 * Settles [stashes] persisted alongside [items] against the state each
 * replacement was loaded in, mirroring the in-process settlement in
 * [DownloadsRepository]: a completed replacement retires its stash, a failed
 * (or vanished, i.e. cancelled) replacement gives the stashed finished item
 * back, and any other replacement keeps its stash for the live callbacks.
 * Pure: the caller performs the file deletions and the persist.
 */
internal fun settlePersistedStashes(
    items: List<DownloadItem>,
    stashes: Map<String, StashedReplacement>,
): StashSettlement {
    if (stashes.isEmpty()) {
        return StashSettlement(
            items = items,
            remainingStashes = emptyMap(),
            completedStashes = emptyList(),
            restoredReplacementFileNames = emptyList(),
        )
    }
    val settled = items.toMutableList()
    val remaining = mutableMapOf<String, StashedReplacement>()
    val completed = mutableListOf<StashedReplacement>()
    val restoredFileNames = mutableListOf<String>()
    for ((replacementId, stash) in stashes) {
        val replacement = settled.firstOrNull { it.id == replacementId }
        when (replacement?.status) {
            DownloadStatus.Completed -> completed += stash
            DownloadStatus.Failed, null -> {
                if (replacement != null) {
                    settled.removeAll { it.id == replacementId }
                    restoredFileNames += replacement.fileName
                }
                if (settled.none { it.id == stash.item.id }) {
                    settled.add(0, stash.item)
                }
            }
            else -> remaining[replacementId] = stash
        }
    }
    return StashSettlement(
        items = settled,
        remainingStashes = remaining,
        completedStashes = completed,
        restoredReplacementFileNames = restoredFileNames,
    )
}

internal fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

internal fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    extension: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

// Most torrent releases are Matroska; the real extension replaces this once the engine resolves the file.
internal const val TORRENT_FALLBACK_EXTENSION = "mkv"

internal data class TorrentDownloadSource(
    val infoHash: String,
    val fileIdx: Int?,
    val trackers: List<String>,
    val filenameHint: String?,
    val magnetUri: String,
)

internal fun StreamItem.torrentDownloadSource(): TorrentDownloadSource? {
    if (!isTorrentStream) return null
    val rawInfoHash = p2pInfoHash ?: return null
    val infoHash = runCatching { canonicalP2pInfoHash(rawInfoHash) }.getOrNull() ?: return null
    val trackers = p2pTrackers
    return TorrentDownloadSource(
        infoHash = infoHash,
        fileIdx = p2pFileIdx,
        trackers = trackers,
        filenameHint = behaviorHints.filename?.trim()?.takeIf { it.isNotEmpty() },
        magnetUri = buildP2pMagnetUri(infoHash, trackers),
    )
}

internal fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

internal fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

internal fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
