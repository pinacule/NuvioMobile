package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TorrentDownloadPhase {
    /** Waiting for a free torrent download slot. */
    Queued,
    FindingPeers,
    Downloading,
}

/**
 * Live swarm figures for a running torrent download. Speed is measured from the bytes this download
 * writes; peer and seed counts come from the engine and cover every torrent it is serving.
 */
data class TorrentDownloadSwarm(
    val phase: TorrentDownloadPhase = TorrentDownloadPhase.FindingPeers,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val uploadSpeedBytesPerSecond: Long = 0L,
    val peers: Int = 0,
    val seeds: Int = 0,
)

/** In-memory only: these change every second and are meaningless after the transfer stops. */
object TorrentDownloadSwarmRepository {
    private val _state = MutableStateFlow<Map<String, TorrentDownloadSwarm>>(emptyMap())
    val state: StateFlow<Map<String, TorrentDownloadSwarm>> = _state.asStateFlow()

    private val lock = SynchronizedObject()
    private val attempts = mutableMapOf<String, Any>()

    fun swarmFor(downloadId: String): TorrentDownloadSwarm? = _state.value[downloadId]

    /** Publishes figures for [attempt], which becomes the download's current attempt. */
    internal fun publish(downloadId: String, swarm: TorrentDownloadSwarm, attempt: Any) = synchronized(lock) {
        attempts[downloadId] = attempt
        _state.update { current -> current + (downloadId to swarm) }
    }

    /** Clears the figures only if [attempt] is still the download's current attempt. */
    internal fun clear(downloadId: String, attempt: Any) = synchronized(lock) {
        if (attempts[downloadId] !== attempt) return@synchronized
        attempts.remove(downloadId)
        _state.update { current -> current - downloadId }
    }
}

internal fun DownloadItem.torrentOutputFileName(fileExtension: String?): String {
    val extension = fileExtension?.normalizedMediaFileExtension() ?: return fileName
    val base = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    return "$base.$extension"
}

/** Returns the lowercase extension of a file path or name when it looks like a real one. */
internal fun String.mediaFileExtensionOrNull(): String? =
    substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast('.', missingDelimiterValue = "")
        .normalizedMediaFileExtension()

private fun String.normalizedMediaFileExtension(): String? =
    trim().lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
