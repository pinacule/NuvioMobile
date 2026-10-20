package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ids of HTTP downloads waiting for a free transfer slot. Torrent downloads report the same thing
 * through [TorrentDownloadPhase.Queued]. In-memory only: the wait ends with the process.
 */
object DownloadQueueState {
    private val _queued = MutableStateFlow<Set<String>>(emptySet())
    val queued: StateFlow<Set<String>> = _queued.asStateFlow()

    fun isQueued(downloadId: String): Boolean = downloadId in _queued.value

    internal fun setQueued(downloadId: String, queued: Boolean) {
        _queued.update { current -> if (queued) current + downloadId else current - downloadId }
    }
}
