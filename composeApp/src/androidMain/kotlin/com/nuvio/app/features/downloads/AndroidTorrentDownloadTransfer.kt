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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_torrent_missing_hash
import nuvio.composeapp.generated.resources.downloads_torrent_stalled
import okhttp3.OkHttpClient
import org.jetbrains.compose.resources.getString

private const val TAG = "TorrentDownload"
private const val SWARM_SAMPLE_INTERVAL_MS = 1_000L
private const val STALL_TIMEOUT_MS = 10L * 60L * 1_000L
private const val LOOPBACK_RETRIES_WITHOUT_PROGRESS = 3
private const val LOOPBACK_RETRY_DELAY_MS = 2_000L

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
    val owner = "download:${item.id}"
    // Identifies this attempt so a superseded attempt's cleanup cannot wipe a newer attempt's stats.
    val attempt = Any()
    var engine: NuvioEngine? = null
    var torrentLease: NuvioEngineHost.TorrentLease? = null
    var streamId: String? = null
    // Pauses, network hiccups and retries keep the torrent and its peers in the engine; it is removed
    // once this file is done or the torrent itself failed.
    var finished = false
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
                val stream = activeEngine.prepareStream(
                    torrentId = lease.torrentId,
                    fileIndex = item.torrentFileIdx,
                    filenameHint = item.torrentFilenameHint,
                )
                streamId = stream.id
                val fileSize = stream.fileSize.takeIf { it > 0L }
                onResolvedFile(item.torrentOutputFileName(resolveFileExtension(activeEngine, lease, stream.fileIndex)))
                val partial = File(directory, "${item.fileName}.part")
                var failuresWithoutProgress = 0
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
 * Publishes swarm figures every second and fails the transfer when no bytes arrive for
 * [STALL_TIMEOUT_MS], which also covers a torrent whose metadata never arrives.
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
    while (true) {
        delay(SWARM_SAMPLE_INTERVAL_MS)
        val now = SystemClock.elapsedRealtime()
        val bytes = writtenBytes.get()
        val delta = if (bytes >= 0L && lastBytes >= 0L) (bytes - lastBytes).coerceAtLeast(0L) else 0L
        val speed = delta * 1_000L / (now - lastSampleAt).coerceAtLeast(1L)
        if (delta > 0L || (bytes >= 0L && lastBytes < 0L)) {
            // The first progress report is the resume offset; the stall clock restarts from there too.
            lastProgressAt = now
        }
        lastBytes = bytes
        lastSampleAt = now
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
