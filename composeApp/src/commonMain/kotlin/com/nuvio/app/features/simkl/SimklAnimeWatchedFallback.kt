package com.nuvio.app.features.simkl

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Provides a fallback watched check for anime franchise-parent content IDs.
 *
 * When meta.id is a franchise parent (e.g. "mal:49233") that doesn't exist in
 * Simkl's snapshot, episodes can't be resolved through watchedKeys alone.
 * This fallback parses the video ID (e.g. "mal:32615:1") and checks the
 * Simkl snapshot directly.
 *
 * Optimistic removals are tracked — after unmark, the videoId is blacklisted
 * until a snapshot arrives that no longer reports it watched. The set is
 * guarded by a lock: it is written from mutation paths and read from badge
 * resolution on background scopes.
 */
object SimklAnimeWatchedFallback {
    private val lock = SynchronizedObject()
    private val optimisticallyRemoved = mutableSetOf<String>()

    fun isWatched(videoId: String, episode: Int): Boolean {
        synchronized(lock) {
            if (videoId in optimisticallyRemoved) return false
        }
        return isWatchedByAnimeVideoId(
            snapshot = SimklSyncRepository.state.value.snapshot,
            videoId = videoId,
            episode = episode,
        )
    }

    fun markOptimisticallyRemoved(videoId: String) {
        synchronized(lock) {
            optimisticallyRemoved += videoId
        }
    }

    /** The videoIds currently blacklisted by an optimistic removal. */
    fun optimisticallyRemovedVideoIds(): Set<String> = synchronized(lock) {
        optimisticallyRemoved.toSet()
    }

    /**
     * Reconciles optimistic removals against a freshly emitted snapshot.
     *
     * An entry is cleared only when [snapshot] no longer reports that videoId
     * watched — i.e. the removal has been confirmed remotely. Entries the
     * snapshot still reports watched stay blacklisted, because that snapshot
     * is stale relative to the unmark; clearing them on any emission (the old
     * behaviour) resurrected the episode as watched until the next refresh.
     */
    fun clearOptimisticRemovals(snapshot: SimklSyncSnapshot) {
        synchronized(lock) {
            optimisticallyRemoved.retainAll { videoId ->
                isWatchedByAnimeVideoId(
                    snapshot = snapshot,
                    videoId = videoId,
                    // The episode segment, when the videoId carries one, is what
                    // isWatchedByAnimeVideoId matches on; the trailing segment is
                    // that episode (or, for season-carrying ids, still the episode).
                    episode = videoId.substringAfterLast(':').toIntOrNull() ?: 0,
                )
            }
        }
    }
}
