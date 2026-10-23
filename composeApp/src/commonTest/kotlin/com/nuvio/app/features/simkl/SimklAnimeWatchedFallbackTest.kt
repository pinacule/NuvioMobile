package com.nuvio.app.features.simkl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Optimistic removals in [SimklAnimeWatchedFallback] must survive snapshot emissions
 * that still report the episode watched (stale snapshot), and be cleared only by a
 * snapshot that confirms the removal. Uses a videoId no other test touches, and
 * leaves the shared object empty again.
 */
class SimklAnimeWatchedFallbackTest {
    private val videoId = "mal:424242:3"

    private fun snapshotWith(episodeThreeWatched: Boolean): SimklSyncSnapshot =
        SimklSyncSnapshot(
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.ANIME,
                    animeType = "tv",
                    status = SimklListStatus.WATCHING,
                    seasons = listOf(
                        SimklSeason(1, listOf(
                            SimklEpisode(
                                3,
                                if (episodeThreeWatched) "2023-11-14T23:20:00Z" else null,
                                SimklEpisodeMapping(1, 3),
                            ),
                        )),
                    ),
                    show = SimklMedia(
                        title = "Fallback Test Anime",
                        year = 2020,
                        ids = buildJsonObject {
                            put("simkl", 90001)
                            put("mal", 424242)
                        },
                    ),
                ),
            ),
        )

    @Test
    fun `optimistic removal survives a snapshot that still reports the episode watched`() {
        SimklAnimeWatchedFallback.markOptimisticallyRemoved(videoId)
        try {
            SimklAnimeWatchedFallback.clearOptimisticRemovals(snapshotWith(episodeThreeWatched = true))
            assertTrue(videoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIds())

            SimklAnimeWatchedFallback.clearOptimisticRemovals(snapshotWith(episodeThreeWatched = false))
            assertFalse(videoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIds())
        } finally {
            SimklAnimeWatchedFallback.clearOptimisticRemovals(SimklSyncSnapshot())
        }
    }

    @Test
    fun `optimistic removals are scoped per profile`() {
        val profileA = 987_001
        val profileB = 987_002
        val scopedVideoId = "mal:515151:3"
        try {
            SimklAnimeWatchedFallback.markOptimisticallyRemovedForProfile(profileA, scopedVideoId)

            // Profile B never unmarked this episode: its blacklist is empty
            // and reconciliation against B's snapshots leaves A's set alone.
            assertTrue(scopedVideoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIdsForProfile(profileA))
            assertFalse(scopedVideoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIdsForProfile(profileB))
            SimklAnimeWatchedFallback.clearOptimisticRemovalsForProfile(profileB, SimklSyncSnapshot())
            assertTrue(scopedVideoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIdsForProfile(profileA))

            // A's confirming snapshot clears only A's entry.
            SimklAnimeWatchedFallback.clearOptimisticRemovalsForProfile(profileA, SimklSyncSnapshot())
            assertFalse(scopedVideoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIdsForProfile(profileA))
        } finally {
            SimklAnimeWatchedFallback.forgetOptimisticRemovals(profileA)
            SimklAnimeWatchedFallback.forgetOptimisticRemovals(profileB)
        }
    }

    @Test
    fun `optimistic removal is cleared by a snapshot without the entry`() {
        SimklAnimeWatchedFallback.markOptimisticallyRemoved(videoId)
        try {
            SimklAnimeWatchedFallback.clearOptimisticRemovals(SimklSyncSnapshot())
            assertFalse(videoId in SimklAnimeWatchedFallback.optimisticallyRemovedVideoIds())
        } finally {
            SimklAnimeWatchedFallback.clearOptimisticRemovals(SimklSyncSnapshot())
        }
    }
}
