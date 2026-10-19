package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Platform side of the silent next-episode preload cache.
 *
 * The cache lives in a dedicated directory (NOT the Downloads dir) and never
 * creates a [DownloadItem]: nothing shows in the Downloads UI.
 */
internal expect object PreloadCachePlatform {
    /**
     * Fetches [item]'s media file into the preload cache dir as [fileName].
     * Returns the local `file:` URI on success, null when the source cannot be
     * fetched on this platform (e.g. torrents where P2P is unsupported).
     * Throws [CancellationException] on cancel; a partial `.part` file is left
     * behind so a later fetch can resume.
     */
    suspend fun fetchMedia(item: DownloadItem, fileName: String): String?

    /** True when a previously cached file URI still exists on disk. */
    fun cachedFileExists(localFileUri: String): Boolean

    /** Deletes a cached media file (not its sidecar subtitles). */
    fun deleteCachedFile(localFileUri: String)

    /** Drops everything in the preload cache dir. */
    fun clearCache()
}

/** One silently preloaded episode: video file + sidecar subtitles in the cache dir. */
internal data class CachedPreload(
    val videoId: String,
    val parentMetaId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val quality: String,
    val localFileUri: String,
    val streamTitle: String,
    val streamSubtitle: String?,
    val providerName: String,
    val providerAddonId: String?,
    val cachedAtEpochMs: Long,
)

/**
 * Registry of silently preloaded episodes. One-episode lookahead only: starting
 * a new preload evicts the previous entry (never the episode currently playing).
 */
internal object PreloadCache {
    private val entries = mutableMapOf<String, CachedPreload>()

    /** Returns the cached episode for [videoId], or null when absent or gone from disk. */
    fun lookup(videoId: String): CachedPreload? {
        val entry = entries[videoId] ?: return null
        return if (PreloadCachePlatform.cachedFileExists(entry.localFileUri)) {
            entry
        } else {
            entries.remove(videoId)
            null
        }
    }

    fun record(entry: CachedPreload) {
        entries[entry.videoId] = entry
    }

    /** Evicts every cached episode except [exceptVideoId] (never delete what's playing). */
    fun evictAllExcept(exceptVideoId: String?) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (videoId, entry) = iterator.next()
            if (videoId == exceptVideoId) continue
            deleteEntry(entry)
            iterator.remove()
        }
    }

    /** Drops the cache for [videoId] once it has been watched (>= 80%). */
    fun onEpisodeWatched(videoId: String) {
        entries.remove(videoId)?.let(::deleteEntry)
    }

    fun clear() {
        evictAllExcept(exceptVideoId = null)
    }

    private fun deleteEntry(entry: CachedPreload) {
        runCatching {
            DownloadSubtitleStorage(entry.localFileUri).remove()
            PreloadCachePlatform.deleteCachedFile(entry.localFileUri)
        }
    }
}

/** Parameters for one silent preload fetch. */
internal data class PreloadFetchRequest(
    /** Synthetic item: never registered in [DownloadsRepository]. */
    val item: DownloadItem,
    val fileName: String,
    val videoId: String,
    val parentMetaId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val quality: String,
    /** Episode currently playing: its cache entry is never evicted. */
    val exceptVideoId: String?,
)

/**
 * Runs the silent preload fetch. Pause cancels the fetch (the partial `.part`
 * is kept); resume restarts it and the transfer continues where it stopped.
 */
internal object PreloadCacheFetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fetchJob: Job? = null
    private var activeRequest: PreloadFetchRequest? = null
    private var pausedRequest: PreloadFetchRequest? = null

    val hasActiveFetch: Boolean get() = fetchJob?.isActive == true
    val hasPausedFetch: Boolean get() = pausedRequest != null

    /** Starts a fetch, evicting any previous cached preload first (one-episode lookahead). */
    fun start(request: PreloadFetchRequest) {
        cancelActive()
        pausedRequest = null
        PreloadCache.evictAllExcept(request.exceptVideoId)
        activeRequest = request
        fetchJob = scope.launch {
            try {
                val uri = PreloadCachePlatform.fetchMedia(request.item, request.fileName)
                if (uri != null) {
                    // Subtitles are fetched now so the cached episode plays fully offline.
                    DownloadSubtitles.prepare(request.item, uri)
                    PreloadCache.record(
                        CachedPreload(
                            videoId = request.videoId,
                            parentMetaId = request.parentMetaId,
                            seasonNumber = request.seasonNumber,
                            episodeNumber = request.episodeNumber,
                            quality = request.quality,
                            localFileUri = uri,
                            streamTitle = request.item.streamTitle,
                            streamSubtitle = request.item.streamSubtitle,
                            providerName = request.item.providerName,
                            providerAddonId = request.item.providerAddonId,
                            cachedAtEpochMs = DownloadsClock.nowEpochMs(),
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Silent cache: failures stay silent, streaming remains the fallback.
            } finally {
                if (activeRequest == request) activeRequest = null
            }
        }
    }

    /** Pauses the active fetch, keeping the partial file for resume. */
    fun pause() {
        val job = fetchJob
        val request = activeRequest
        if (job?.isActive == true && request != null) {
            pausedRequest = request
        }
        cancelActive()
    }

    /** Resumes a paused fetch (call only on WiFi). */
    fun resume() {
        val request = pausedRequest ?: return
        if (hasActiveFetch) return
        pausedRequest = null
        start(request)
    }

    private fun cancelActive() {
        fetchJob?.cancel()
        fetchJob = null
        activeRequest = null
    }
}
