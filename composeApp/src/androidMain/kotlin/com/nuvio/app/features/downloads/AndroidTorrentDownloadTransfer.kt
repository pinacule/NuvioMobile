package com.nuvio.app.features.downloads

import android.os.SystemClock
import android.util.Log
import com.nuvio.app.core.i18n.localizedP2pUnknownTorrentError
import com.nuvio.app.features.p2p.NuvioEngineHost
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEventType
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_torrent_missing_hash
import nuvio.composeapp.generated.resources.downloads_torrent_stalled
import okhttp3.OkHttpClient
import org.jetbrains.compose.resources.getString

private const val TAG = "TorrentDownload"
private const val SWARM_SAMPLE_INTERVAL_MS = 1_000L
/**
 * Samples kept for the rolling speed average. The loopback read stalls for a sample whenever the
 * next sequential piece has not arrived yet, so a single 1 s window regularly reads 0 B/s even
 * while the engine pulls other pieces at full speed. Averaging over [SPEED_WINDOW_SAMPLES]
 * windows shows the sustained rate instead of that sawtooth.
 */
private const val SPEED_WINDOW_SAMPLES = 5
private const val STALL_TIMEOUT_MS = 10L * 60L * 1_000L
private const val LOOPBACK_RETRIES_WITHOUT_PROGRESS = 3
private const val LOOPBACK_RETRY_DELAY_MS = 2_000L
/**
 * How many times in a row a stale loopback stream (HTTP 403/404/410: the engine dropped the
 * session mid-transfer) is re-prepared via prepareStream before giving up. The budget is
 * consecutive, not lifetime: a refresh followed by real download progress re-arms it (see
 * [StreamRefreshBudget]), so a long download whose stream expires at well-spaced intervals
 * survives, while a stream that keeps dying without progress still burns out.
 * Mirrors the HTTP download source-refresh budget (MAX_DOWNLOAD_SOURCE_REFRESHES).
 * Playback re-prepares on every play; downloads must do the same when the stream
 * goes stale, instead of retrying the dead URL.
 */
private const val MAX_LOOPBACK_STREAM_REFRESHES = 2

/**
 * Torrent downloads share one engine and one connection budget; running a whole season at once would
 * split the bandwidth across every episode, so the rest wait in a queue.
 */
private const val MAX_CONCURRENT_TORRENT_DOWNLOADS = 2
internal val torrentDownloadSlots = Semaphore(MAX_CONCURRENT_TORRENT_DOWNLOADS)

/**
 * Hands out torrent download slots in series-episode order (E1 before E2, ...). Workers start in
 * whatever order WorkManager/JobScheduler runs them, so a bare semaphore would let a later
 * episode grab a free slot first; the ordered queue guarantees first-episode-first no matter the
 * arrival order.
 *
 * Entries are (item, token): the token identifies one acquire attempt so a cancelled attempt
 * removes exactly its own entry. The queue is in-memory only; across a process restart workers
 * re-insert themselves in episode order as they arrive.
 */
private val torrentSlotQueueMutex = Mutex()
private val torrentSlotQueue = ArrayDeque<Pair<DownloadItem, Any>>()
private const val TORRENT_SLOT_POLL_MS = 250L

internal suspend fun acquireOrderedTorrentSlot(item: DownloadItem, token: Any) {
    torrentSlotQueueMutex.withLock {
        val queue = torrentSlotQueue
        val index = queue.indexOfFirst { (queued, _) ->
            downloadSeriesEpisodeComparator.compare(item, queued) < 0
        }
        if (index < 0) queue.addLast(item to token) else queue.add(index, item to token)
    }
    try {
        while (true) {
            val acquired = torrentSlotQueueMutex.withLock {
                if (torrentSlotQueue.firstOrNull()?.let { (head, _) -> head.id == item.id } == true &&
                    torrentDownloadSlots.tryAcquire()
                ) {
                    torrentSlotQueue.removeFirst()
                    true
                } else {
                    false
                }
            }
            if (acquired) return
            delay(TORRENT_SLOT_POLL_MS)
        }
    } catch (cancellation: CancellationException) {
        torrentSlotQueueMutex.withLock {
            torrentSlotQueue.removeAll { (_, queuedToken) -> queuedToken === token }
        }
        throw cancellation
    }
}

/**
 * The engine's loopback server blocks a read until the pieces behind it are verified, so a slow swarm
 * must not trip a socket timeout. Stalls are detected from download progress instead.
 */
internal val torrentDownloadHttpClient: OkHttpClient = downloadHttpClient.newBuilder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

/** A torrent failure retrying cannot fix; it fails the download with this message. */
internal class TorrentDownloadException(message: String) : Exception(message)

/**
 * Budget for re-preparing a stale loopback stream during one torrent download run.
 *
 * The budget counts CONSECUTIVE refreshes without progress, not refreshes over the
 * whole run: each consumed refresh records the transferred-byte high-water mark at
 * that moment, and when a later attempt dies stale after transferring beyond that
 * mark, the count resets to zero before the refresh is consumed. A stream that
 * expires repeatedly at well-spaced points of a long download therefore never
 * burns out, while a stream that keeps dying at the same byte position exhausts
 * the budget after [maxConsecutiveRefreshes] refreshes, exactly like the old
 * lifetime cap did for back-to-back deaths.
 */
internal class StreamRefreshBudget(private val maxConsecutiveRefreshes: Int) {
    var consecutiveRefreshes: Int = 0
        private set
    private var bytesAtLastRefresh: Long = 0L

    /**
     * Records a stale-stream death at [transferredBytes] total bytes on disk and
     * returns true when a re-preparation may be consumed. A failed re-preparation
     * still counts as consumed, mirroring the previous lifetime counter.
     */
    fun tryConsumeRefresh(transferredBytes: Long): Boolean {
        if (transferredBytes > bytesAtLastRefresh) {
            // Real progress since the last refresh: the deaths are spaced out,
            // not a no-progress loop, so the budget starts over.
            consecutiveRefreshes = 0
        }
        if (consecutiveRefreshes >= maxConsecutiveRefreshes) return false
        consecutiveRefreshes++
        bytesAtLastRefresh = transferredBytes
        return true
    }
}

/**
 * Downloads one file of a torrent into [directory] by reading the engine's loopback stream into the
 * regular `.part` file, which keeps resume and finalization identical to HTTP downloads.
 *
 * [onResolvedFile] receives the output file name, with the real extension of the file inside the
 * torrent, before any bytes are written.
 */
internal suspend fun transferTorrentDownload(
    item: DownloadItem,
    directory: File,
    onResolvedFile: suspend (outputFileName: String) -> Unit,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File {
    val infoHash = item.torrentInfoHash?.takeIf { it.isNotBlank() }
        ?: throw TorrentDownloadException(getString(Res.string.downloads_torrent_missing_hash))
    val owner = "$TORRENT_DOWNLOAD_OWNER_PREFIX${item.id}"
    // Identifies this attempt so a superseded attempt's cleanup cannot wipe a newer attempt's stats.
    val attempt = Any()
    var engine: NuvioEngine? = null
    var torrentLease: NuvioEngineHost.TorrentLease? = null
    var streamId: String? = null
    // Pauses, network hiccups and retries keep the torrent and its peers in the engine; it is removed
    // once this file is done or the torrent itself failed.
    var finished = false
    TorrentDownloadSwarmRepository.publish(item.id, TorrentDownloadSwarm(TorrentDownloadPhase.Queued), attempt)
    var slotAcquired = false
    try {
        acquireOrderedTorrentSlot(item, attempt)
        slotAcquired = true
    } catch (cancellation: CancellationException) {
        TorrentDownloadSwarmRepository.clear(item.id, attempt)
        throw cancellation
    }
    try {
        val activeEngine = NuvioEngineHost.acquireEngine(owner)
        engine = activeEngine
        TorrentDownloadSwarmRepository.publish(item.id, swarmFrom(activeEngine, speed = 0L), attempt)
        val lease = NuvioEngineHost.acquireTorrent(
            activeEngine = activeEngine,
            infoHash = infoHash,
            trackers = item.torrentTrackers,
        )
        torrentLease = lease
        val writtenBytes = AtomicLong(-1L)
        return coroutineScope {
            val torrentErrors = launch {
                activeEngine.events.collect { event ->
                    if (event.type == NuvioEventType.TorrentError &&
                        event.requestId == 0L &&
                        event.torrentId == lease.torrentId
                    ) {
                        throw TorrentDownloadException(
                            event.message?.trim()?.takeIf(String::isNotEmpty) ?: localizedP2pUnknownTorrentError(),
                        )
                    }
                }
            }
            val swarm = launch { sampleSwarm(item.id, attempt, activeEngine, writtenBytes) }
            try {
                // Serialized app-wide (see NuvioEngineHost.withPrepareStreamLock):
                // the native engine fails a second concurrent preparation with
                // "stream preparation already pending". This covers both queued
                // downloads and the silent preload, which share this function.
                // `var`: the loopback URL can go stale mid-transfer (engine dropped
                // the session → HTTP 404); the retry loop below re-prepares to get
                // a fresh URL instead of retrying the dead one.
                var stream = try {
                    NuvioEngineHost.withPrepareStreamLock {
                        activeEngine.prepareStream(
                            torrentId = lease.torrentId,
                            fileIndex = item.torrentFileIdx,
                            filenameHint = item.torrentFilenameHint,
                        )
                    }
                } catch (timeout: TimeoutCancellationException) {
                    // Retryable (IOException): the job backs off and tries again
                    // instead of surfacing the raw engine message.
                    throw IOException("Timed out waiting for stream preparation", timeout)
                }
                streamId = stream.id
                var fileSize = stream.fileSize.takeIf { it > 0L }
                onResolvedFile(item.torrentOutputFileName(resolveFileExtension(activeEngine, lease, stream.fileIndex)))
                val partial = File(directory, "${item.fileName}.part")
                var failuresWithoutProgress = 0
                val streamRefreshBudget = StreamRefreshBudget(MAX_LOOPBACK_STREAM_REFRESHES)
                while (true) {
                    val bytesBefore = partial.length()
                    try {
                        return@coroutineScope transferAndroidDownload(
                            item = item.copy(sourceUrl = stream.url, sourceHeaders = emptyMap()),
                            directory = directory,
                            // The loopback route is addressed by info hash; there is no HTTP validator to check.
                            validator = null,
                            client = torrentDownloadHttpClient,
                            onHeaders = { total, _ -> onHeaders(total ?: fileSize, null) },
                            onProgress = { bytes, total ->
                                writtenBytes.set(bytes)
                                onProgress(bytes, total ?: fileSize)
                            },
                        ).also { finished = true }
                    } catch (error: IOException) {
                        // A dropped loopback read is resumed in place while the stream and peers stay up;
                        // only repeated failures without progress go back to the job's retry backoff.
                        ensureActive()
                        // Stale stream: the engine dropped the session mid-transfer (HTTP 403/404/410 on the
                        // loopback URL). Retrying the dead URL is futile; re-prepare through the engine
                        // for a fresh URL, exactly as playback does on every play. Same torrent/file, so
                        // the partial remains valid and the download resumes instead of restarting.
                        // The budget (MAX_LOOPBACK_STREAM_REFRESHES) counts consecutive refreshes
                        // without progress: progress past the last refresh's byte mark re-arms it.
                        if (error is DownloadHttpException &&
                            error.statusCode in STALE_HTTP_STATUS_CODES &&
                            streamRefreshBudget.tryConsumeRefresh(partial.length())
                        ) {
                            Log.w(TAG, "Torrent loopback stream stale (HTTP ${error.statusCode}); re-preparing (${streamRefreshBudget.consecutiveRefreshes}/$MAX_LOOPBACK_STREAM_REFRESHES)", error)
                            val refreshed = try {
                                streamId?.let { id ->
                                    try {
                                        activeEngine.stopStream(id)
                                    } catch (stopError: Exception) {
                                        Log.w(TAG, "Error stopping stale torrent stream", stopError)
                                    }
                                }
                                NuvioEngineHost.withPrepareStreamLock {
                                    activeEngine.prepareStream(
                                        torrentId = lease.torrentId,
                                        fileIndex = item.torrentFileIdx,
                                        filenameHint = item.torrentFilenameHint,
                                    )
                                }
                            } catch (refreshError: Exception) {
                                // Genuine cancellation propagates; a lock timeout is NOT
                                // cancellation — withPrepareStreamLock's contract requires
                                // translating it into a retryable failure, never surfacing
                                // it raw (the scheduler treats CancellationException as
                                // "cancelled, no retry"). Fall through to the normal
                                // retry/backoff path below, like the initial prepare does.
                                if (refreshError is CancellationException &&
                                    refreshError !is TimeoutCancellationException
                                ) {
                                    throw refreshError
                                }
                                Log.w(TAG, "Torrent stream re-preparation failed", refreshError)
                                null
                            }
                            if (refreshed != null) {
                                streamId = refreshed.id
                                stream = refreshed
                                fileSize = refreshed.fileSize.takeIf { it > 0L }
                                failuresWithoutProgress = 0
                                delay(LOOPBACK_RETRY_DELAY_MS)
                                continue
                            }
                            // Re-preparation failed: fall through to the normal retry/backoff path below.
                        }
                        failuresWithoutProgress = if (partial.length() > bytesBefore) 0 else failuresWithoutProgress + 1
                        if (failuresWithoutProgress >= LOOPBACK_RETRIES_WITHOUT_PROGRESS) throw error
                        Log.w(TAG, "Torrent loopback read interrupted; resuming", error)
                        delay(LOOPBACK_RETRY_DELAY_MS)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Torrent transfer loop exited without a result")
            } finally {
                torrentErrors.cancel()
                swarm.cancel()
            }
        }
    } catch (error: TorrentDownloadException) {
        finished = true
        throw error
    } finally {
        withContext(NonCancellable) {
            streamId?.let { id ->
                try {
                    engine?.stopStream(id)
                } catch (error: Exception) {
                    Log.w(TAG, "Error stopping torrent download stream", error)
                }
            }
            // Removed from the engine only when no other download or playback stream still uses it.
            torrentLease?.let { NuvioEngineHost.releaseTorrent(it, removeWhenUnused = finished) }
            NuvioEngineHost.releaseEngine(owner)
            TorrentDownloadSwarmRepository.clear(item.id, attempt)
            // A cancelled wait never held a permit; releasing one would inflate the semaphore.
            if (slotAcquired) torrentDownloadSlots.release()
        }
    }
}

private suspend fun resolveFileExtension(
    engine: NuvioEngine,
    lease: NuvioEngineHost.TorrentLease,
    fileIndex: Int,
): String? = try {
    engine.files(lease.torrentId)
        .firstOrNull { it.index == fileIndex }
        ?.path
        ?.mediaFileExtensionOrNull()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Log.w(TAG, "Could not read the torrent file list", error)
    null
}

/**
 * Publishes smoothed swarm figures every second and fails the transfer when no bytes arrive for
 * [STALL_TIMEOUT_MS], which also covers a torrent whose metadata never arrives.
 *
 * The published speed is a rolling average over the last [SPEED_WINDOW_SAMPLES] samples rather
 * than the raw 1 s delta: the loopback read blocks until the next sequential piece is verified,
 * so a single sample often reads 0 B/s mid-download. Stall detection still uses the raw
 * progress, so a genuinely stuck torrent fails exactly as before.
 */
private suspend fun sampleSwarm(
    downloadId: String,
    attempt: Any,
    engine: NuvioEngine,
    writtenBytes: AtomicLong,
) {
    var lastBytes = writtenBytes.get()
    var lastSampleAt = SystemClock.elapsedRealtime()
    var lastProgressAt = lastSampleAt
    val recentSamples = ArrayDeque<Pair<Long, Long>>()
    while (true) {
        delay(SWARM_SAMPLE_INTERVAL_MS)
        val now = SystemClock.elapsedRealtime()
        val bytes = writtenBytes.get()
        val delta = if (bytes >= 0L && lastBytes >= 0L) (bytes - lastBytes).coerceAtLeast(0L) else 0L
        if (delta > 0L || (bytes >= 0L && lastBytes < 0L)) {
            // The first progress report is the resume offset; the stall clock restarts from there too.
            lastProgressAt = now
        }
        lastBytes = bytes
        val sampleMs = (now - lastSampleAt).coerceAtLeast(1L)
        lastSampleAt = now
        recentSamples.addLast(delta to sampleMs)
        while (recentSamples.size > SPEED_WINDOW_SAMPLES) recentSamples.removeFirst()
        val windowBytes = recentSamples.sumOf { it.first }
        val windowMs = recentSamples.sumOf { it.second }.coerceAtLeast(1L)
        val speed = windowBytes * 1_000L / windowMs
        TorrentDownloadSwarmRepository.publish(downloadId, swarmFrom(engine, speed), attempt)
        if (now - lastProgressAt >= STALL_TIMEOUT_MS) {
            throw TorrentDownloadException(getString(Res.string.downloads_torrent_stalled))
        }
    }
}

/** "Finding peers" means no peer is connected; a slow but connected swarm still shows its figures. */
private fun swarmFrom(engine: NuvioEngine, speed: Long): TorrentDownloadSwarm {
    val stats = engine.stats.value
    return TorrentDownloadSwarm(
        phase = if (stats.connectedPeers == 0) TorrentDownloadPhase.FindingPeers else TorrentDownloadPhase.Downloading,
        downloadSpeedBytesPerSecond = speed,
        uploadSpeedBytesPerSecond = stats.uploadRateBytesPerSecond,
        peers = stats.connectedPeers,
        seeds = stats.connectedSeeds,
    )
}
