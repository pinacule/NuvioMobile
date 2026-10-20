package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

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

    /**
     * Deletes the partial `.part` file for [fileName], if any — e.g. when a
     * fetch is superseded by a different file and will never resume.
     */
    fun deletePartialFile(fileName: String)

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
 *
 * Threading: reads go through a lock-free volatile snapshot (safe from any
 * thread); all mutations are serialized through [mutex].
 */
internal object PreloadCache {
    private val mutex = Mutex()
    private val mutableEntries = mutableMapOf<String, CachedPreload>()
    private val mutableEligible = mutableSetOf<String>()

    @Volatile
    private var snapshot: Map<String, CachedPreload> = emptyMap()

    /**
     * Returns the cached episode for [videoId], or null when absent or gone
     * from disk. Lock-free; safe to call from any thread.
     */
    fun lookup(videoId: String): CachedPreload? {
        val entry = snapshot[videoId] ?: return null
        return if (PreloadCachePlatform.cachedFileExists(entry.localFileUri)) {
            entry
        } else {
            null
        }
    }

    suspend fun record(entry: CachedPreload) {
        mutex.withLock {
            mutableEntries[entry.videoId] = entry
            mutableEligible.remove(entry.videoId)
            snapshot = mutableEntries.toMap()
        }
    }

    /** Evicts every cached episode except [exceptVideoId] (never delete what's playing). */
    suspend fun evictAllExcept(exceptVideoId: String?) {
        val evicted = mutex.withLock {
            val doomed = mutableEntries.filterKeys { it != exceptVideoId }
            doomed.keys.forEach { mutableEntries.remove(it) }
            mutableEligible.removeAll(doomed.keys)
            snapshot = mutableEntries.toMap()
            doomed.values.toList()
        }
        evicted.forEach(::deleteEntry)
    }

    /**
     * Marks a cached episode as watched enough (>= 80%) to be evicted once it
     * is no longer playing. Never deletes anything by itself.
     */
    suspend fun markWatchedEligible(videoId: String) {
        mutex.withLock {
            if (mutableEntries.containsKey(videoId)) mutableEligible.add(videoId)
        }
    }

    /**
     * Deletes watched-eligible entries, but never [currentVideoId] — the file
     * of the episode still playing must not be unlinked mid-playback. Call
     * when an episode is replaced or when the player exits (pass null then).
     */
    suspend fun evictEligibleExcept(currentVideoId: String?) {
        val evicted = mutex.withLock {
            val doomed = mutableEligible.filter { it != currentVideoId }
            mutableEligible.removeAll(doomed.toSet())
            val entries = doomed.mapNotNull { mutableEntries.remove(it) }
            snapshot = mutableEntries.toMap()
            entries
        }
        evicted.forEach(::deleteEntry)
    }

    suspend fun clear() {
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

    /**
     * The most recently cancelled fetch job (R-9). Cancellation is not
     * completion: a cancelled transfer can still be unwinding (NonCancellable
     * cleanup, `.part` appends) after [cancelActive] returns. A replacement
     * fetch joins this job before touching any file, so the two never run
     * concurrently on the same `.part`.
     */
    private var cancelledFetchJob: Job? = null

    val hasActiveFetch: Boolean get() = fetchJob?.isActive == true
    val hasPausedFetch: Boolean get() = pausedRequest != null

    /** Starts a fetch, evicting any previous cached preload first (one-episode lookahead). */
    fun start(request: PreloadFetchRequest) {
        // Requests dropped here will never resume: delete their partial files now
        // (pause keeps its own partial for resume).
        val droppedPartials = listOfNotNull(activeRequest, pausedRequest)
            .filter { it.fileName != request.fileName }
            .map { it.fileName }
            .distinct()
        cancelActive()
        pausedRequest = null
        activeRequest = request
        // Consume the superseded job (from a pause or a replaced fetch): the
        // new fetch waits for its unwind before touching any file (R-9).
        // start() itself stays non-blocking; the join happens inside the
        // fetch coroutine.
        val supersededFetch = cancelledFetchJob
        cancelledFetchJob = null
        fetchJob = scope.launch {
            try {
                supersededFetch?.join()
                PreloadCache.evictAllExcept(request.exceptVideoId)
                droppedPartials.forEach { PreloadCachePlatform.deletePartialFile(it) }
                val uri = PreloadCachePlatform.fetchMedia(request.item, request.fileName)
                if (uri != null) {
                    // Record FIRST: a subtitle failure must not orphan the video file.
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
                    // Subtitles are fetched now so the cached episode plays fully offline.
                    DownloadSubtitles.prepare(request.item, uri)
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
        val job = fetchJob
        if (job != null) {
            job.cancel()
            cancelledFetchJob = job
        }
        fetchJob = null
        activeRequest = null
    }
}
