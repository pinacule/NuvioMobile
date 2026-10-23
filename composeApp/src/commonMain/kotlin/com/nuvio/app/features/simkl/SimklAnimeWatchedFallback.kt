package com.nuvio.app.features.simkl

import com.nuvio.app.features.profiles.ProfileRepository
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
 * until a snapshot arrives that no longer reports it watched. The sets are
 * guarded by a lock: they are written from mutation paths and read from
 * badge resolution on background scopes.
 *
 * Removals are scoped per profile: the blacklist records an unmark made
 * against one profile's Simkl account, and profiles can hold different
 * accounts (or the same videoId under different accounts), so one global
 * set would hide an episode another profile has legitimately watched — and
 * a profile switch must not carry the previous profile's pending removals
 * into the next profile's badge checks. A profile's set is kept across a
 * switch away and back (its confirming snapshot may only arrive later); it
 * is dropped when the profile is deleted and cleared for all profiles on
 * disconnect.
 */
object SimklAnimeWatchedFallback {
    private val lock = SynchronizedObject()
    private val optimisticallyRemovedByProfile = mutableMapOf<Int, MutableSet<String>>()

    fun isWatched(videoId: String, episode: Int): Boolean =
        isWatchedForProfile(ProfileRepository.activeProfileId, videoId, episode)

    internal fun isWatchedForProfile(profileId: Int, videoId: String, episode: Int): Boolean {
        synchronized(lock) {
            if (videoId in optimisticallyRemovedByProfile[profileId].orEmpty()) return false
        }
        return isWatchedByAnimeVideoId(
            snapshot = SimklSyncRepository.state.value.snapshot,
            videoId = videoId,
            episode = episode,
        )
    }

    fun markOptimisticallyRemoved(videoId: String) {
        markOptimisticallyRemovedForProfile(ProfileRepository.activeProfileId, videoId)
    }

    internal fun markOptimisticallyRemovedForProfile(profileId: Int, videoId: String) {
        synchronized(lock) {
            optimisticallyRemovedByProfile.getOrPut(profileId) { mutableSetOf() } += videoId
        }
    }

    /** The videoIds currently blacklisted by an optimistic removal. */
    fun optimisticallyRemovedVideoIds(): Set<String> =
        optimisticallyRemovedVideoIdsForProfile(ProfileRepository.activeProfileId)

    internal fun optimisticallyRemovedVideoIdsForProfile(profileId: Int): Set<String> =
        synchronized(lock) {
            optimisticallyRemovedByProfile[profileId]?.toSet().orEmpty()
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
        clearOptimisticRemovalsForProfile(ProfileRepository.activeProfileId, snapshot)
    }

    internal fun clearOptimisticRemovalsForProfile(profileId: Int, snapshot: SimklSyncSnapshot) {
        synchronized(lock) {
            val removed = optimisticallyRemovedByProfile[profileId] ?: return
            removed.retainAll { videoId ->
                isWatchedByAnimeVideoId(
                    snapshot = snapshot,
                    videoId = videoId,
                    // The episode segment, when the videoId carries one, is what
                    // isWatchedByAnimeVideoId matches on; the trailing segment is
                    // that episode (or, for season-carrying ids, still the episode).
                    episode = videoId.substringAfterLast(':').toIntOrNull() ?: 0,
                )
            }
            if (removed.isEmpty()) {
                optimisticallyRemovedByProfile.remove(profileId)
            }
        }
    }

    /** Drops one profile's pending removals (the profile is gone). */
    fun forgetOptimisticRemovals(profileId: Int) {
        synchronized(lock) {
            optimisticallyRemovedByProfile.remove(profileId)
        }
    }

    /** Drops every profile's pending removals (disconnect / local wipe). */
    fun clearAllOptimisticRemovals() {
        synchronized(lock) {
            optimisticallyRemovedByProfile.clear()
        }
    }
}
