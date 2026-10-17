package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.DesktopPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal actual object P2pSettingsStorage {
    private val prefs = DesktopPreferences("p2psettingsstorage")

    actual fun loadP2pEnabled(): Boolean? = prefs.getBoolean("p2p_enabled")
    actual fun saveP2pEnabled(enabled: Boolean) = prefs.putBoolean("p2p_enabled", enabled)
    actual fun loadEnableUpload(): Boolean? = prefs.getBoolean("enable_upload")
    actual fun saveEnableUpload(enabled: Boolean) = prefs.putBoolean("enable_upload", enabled)
    actual fun loadHideTorrentStats(): Boolean? = prefs.getBoolean("hide_torrent_stats")
    actual fun saveHideTorrentStats(enabled: Boolean) = prefs.putBoolean("hide_torrent_stats", enabled)
    actual fun loadTorrentProfile(): String? = prefs.getString("torrent_profile")
    actual fun saveTorrentProfile(profile: String) = prefs.putString("torrent_profile", profile)
    actual fun loadCacheSize(): String? = prefs.getString("cache_size")
    actual fun saveCacheSize(size: String) = prefs.putString("cache_size", size)
}

private data class TorrServerFile(
    val id: Long,
    val path: String,
    val length: Long,
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "webm", "ts", "m2ts", "mts", "mov", "wmv", "flv", "ogv",
)

private fun JsonObject.files(): List<TorrServerFile> {
    val array = this["file_stats"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val length = (obj["length"] as? JsonPrimitive)?.longOrNull ?: 0L
        TorrServerFile(id, path, length)
    }
}

/**
 * Desktop torrent streaming backed by a local TorrServer subprocess
 * (AUR `torrserver-bin`, or `NUVIO_TORRSERVER_URL`).
 *
 * Streaming is disabled by default via [com.nuvio.app.core.build.AppFeaturePolicy.p2pEnabled].
 */
actual object P2pStreamingEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = TorrServerManager()

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private var currentHash: String? = null
    private var currentMagnet: String? = null
    private var currentFileId: Long = -1
    private var statsJob: Job? = null

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        stopStreamLocked()
        _state.value = P2pStreamingState.Connecting(phase = "starting_engine")
        val infoHash = canonicalP2pInfoHash(request.infoHash)
        try {
            manager.ensureRunning()
            _state.value = P2pStreamingState.Connecting(phase = "adding_torrent")
            val magnet = buildP2pMagnetUri(infoHash, request.trackers)
            currentMagnet = magnet
            val added = manager.addTorrent(magnet, request.filename ?: infoHash)
            val hash = TorrServerManager.stringField(added, "hash") ?: infoHash
            currentHash = hash

            _state.value = P2pStreamingState.Connecting(phase = "fetching_metadata")
            val torrent = withTimeout(120_000) {
                var latest: JsonObject? = null
                while (true) {
                    val current = manager.getTorrent(hash)
                    latest = current ?: latest
                    if (current != null && current.files().isNotEmpty()) break
                    delay(500)
                }
                latest!!
            }
            val file = pickFile(torrent, request)
                ?: throw P2pStreamingException("No playable file in torrent $hash")
            currentFileId = file.id

            val url = manager.streamUrl(hash, file.id, file.path)
            _state.value = P2pStreamingState.Connecting(phase = "starting_stream")
            startStatsPolling(hash, url)
            _state.value = P2pStreamingState.Streaming(
                localUrl = url,
                downloadSpeed = 0L,
                uploadSpeed = 0L,
                peers = 0,
                seeds = 0,
                bufferProgress = 0f,
                totalProgress = 0f,
            )
            url
        } catch (e: Exception) {
            if (e is P2pStreamingException) {
                _state.value = P2pStreamingState.Error(e.message ?: "Streaming failed")
                throw e
            }
            _state.value = P2pStreamingState.Error(e.message ?: "Streaming failed")
            throw P2pStreamingException(e.message ?: "Streaming failed")
        }
    }

    actual suspend fun clearCache(): P2pCacheClearResult = withContext(Dispatchers.IO) {
        // TorrServer owns its cache; drop every torrent except the active stream.
        val active = currentHash
        val dropped = manager.listTorrents()
            .mapNotNull { TorrServerManager.stringField(it, "hash") }
            .filter { it != active }
        dropped.forEach { manager.dropTorrent(it) }
        _cacheState.value = P2pCacheUiState(hasMeasurement = true)
        P2pCacheClearResult(reclaimedBytes = 0L, remainingBytes = 0L, protectedBytes = 0L)
    }

    actual fun stopStream() {
        scope.launch {
            stopStreamLocked()
        }
    }

    actual fun shutdown() {
        scope.launch {
            stopStreamLocked()
            manager.shutdown()
        }
    }

    private suspend fun stopStreamLocked() {
        statsJob?.cancelAndJoin()
        statsJob = null
        currentHash?.let { manager.dropTorrent(it) }
        currentHash = null
        currentMagnet = null
        currentFileId = -1
        _state.value = P2pStreamingState.Idle
    }

    private fun pickFile(torrent: JsonObject, request: P2pStreamRequest): TorrServerFile? {
        val files = torrent.files()
        if (files.isEmpty()) return null
        request.fileIdx?.let { wanted ->
            files.firstOrNull { it.id == wanted.toLong() }?.let { return it }
        }
        request.filename?.takeIf { it.isNotBlank() }?.let { wanted ->
            files.firstOrNull { it.path.endsWith(wanted, ignoreCase = true) }?.let { return it }
        }
        return files.filter { file ->
            file.path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        }.maxByOrNull { it.length } ?: files.maxByOrNull { it.length }
    }

    private fun startStatsPolling(hash: String, url: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                delay(2_000)
                val torrent = manager.getTorrent(hash) ?: break
                val downloadSpeed = TorrServerManager.longField(torrent, "download_speed") ?: 0L
                val uploadSpeed = TorrServerManager.longField(torrent, "upload_speed") ?: 0L
                val peers = TorrServerManager.intField(torrent, "active_peers", "peers", "total_peers") ?: 0
                val totalSize = TorrServerManager.longField(torrent, "torrent_size") ?: 0L
                _state.value = P2pStreamingState.Streaming(
                    localUrl = url,
                    downloadSpeed = downloadSpeed,
                    uploadSpeed = uploadSpeed,
                    peers = peers,
                    seeds = 0,
                    bufferProgress = 0f,
                    totalProgress = 0f,
                    downloadedBytes = totalSize,
                )
            }
        }
    }
}
