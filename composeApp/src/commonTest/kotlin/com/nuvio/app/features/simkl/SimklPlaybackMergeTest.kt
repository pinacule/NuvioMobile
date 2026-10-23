package com.nuvio.app.features.simkl

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimklPlaybackMergeTest {
    // 2023-11-14T22:13:20Z, the snapshot's last successful sync in these tests.
    private val lastSyncedAtEpochMs = 1_700_000_000_000L

    @Test
    fun `held row absent from the fetch and paused after the last sync is kept`() {
        val fetchedRow = episodePlayback(episode = 3, pausedAt = "2023-11-14T21:00:00Z")
        val heldRow = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-14T22:14:20Z",
        )

        val merged = mergeFetchedPlayback(
            fetched = listOf(fetchedRow),
            held = listOf(heldRow),
            lastSyncedAtEpochMs = lastSyncedAtEpochMs,
        )

        assertEquals(listOf(fetchedRow, heldRow), merged)
    }

    @Test
    fun `held row absent from the fetch and paused before the last sync is dropped`() {
        val fetchedRow = episodePlayback(episode = 3, pausedAt = "2023-11-14T21:00:00Z")
        val heldRow = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-14T22:12:20Z",
        )

        val merged = mergeFetchedPlayback(
            fetched = listOf(fetchedRow),
            held = listOf(heldRow),
            lastSyncedAtEpochMs = lastSyncedAtEpochMs,
        )

        assertEquals(listOf(fetchedRow), merged)
    }

    @Test
    fun `held row absent from the fetch without a last sync time is dropped`() {
        val heldRow = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-14T22:14:20Z",
        )

        val merged = mergeFetchedPlayback(
            fetched = emptyList(),
            held = listOf(heldRow),
            lastSyncedAtEpochMs = null,
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `held row present in the fetch still wins only when newer`() {
        val fetchedRow = episodePlayback(
            episode = 5,
            progress = 40.0,
            pausedAt = "2023-11-14T22:13:20Z",
        )
        val newerHeld = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-14T22:14:20Z",
        )
        val olderHeld = episodePlayback(
            episode = 5,
            progress = 20.0,
            pausedAt = "2023-11-14T22:12:20Z",
        )

        assertEquals(
            listOf(newerHeld),
            mergeFetchedPlayback(
                fetched = listOf(fetchedRow),
                held = listOf(newerHeld),
                lastSyncedAtEpochMs = lastSyncedAtEpochMs,
            ),
        )
        assertEquals(
            listOf(fetchedRow),
            mergeFetchedPlayback(
                fetched = listOf(fetchedRow),
                held = listOf(olderHeld),
                lastSyncedAtEpochMs = lastSyncedAtEpochMs,
            ),
        )
    }

    @Test
    fun `held row leading the fetch by days loses to the fetched row`() {
        // The held stamp is device wall-clock, the fetched stamp is server
        // time. A two-day lead is not publish lag — it is a skewed device
        // clock — so the account's genuinely newer position must win instead
        // of being pinned under the stale held row.
        val fetchedRow = episodePlayback(
            episode = 5,
            progress = 40.0,
            pausedAt = "2023-11-14T22:13:20Z",
        )
        val skewedHeld = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-16T22:13:20Z",
        )

        assertEquals(
            listOf(fetchedRow),
            mergeFetchedPlayback(
                fetched = listOf(fetchedRow),
                held = listOf(skewedHeld),
                lastSyncedAtEpochMs = lastSyncedAtEpochMs,
                fetchedAtEpochMs = lastSyncedAtEpochMs,
            ),
        )
    }

    @Test
    fun `held row stamped implausibly after the fetch loses, one minted mid-sync wins`() {
        val fetchedRow = episodePlayback(
            episode = 5,
            progress = 40.0,
            pausedAt = "2023-11-14T22:13:20Z",
        )
        val futureHeld = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-14T23:13:20Z",
        )
        val midSyncHeld = episodePlayback(
            episode = 5,
            progress = 60.0,
            pausedAt = "2023-11-14T22:15:20Z",
        )

        assertEquals(
            listOf(fetchedRow),
            mergeFetchedPlayback(
                fetched = listOf(fetchedRow),
                held = listOf(futureHeld),
                lastSyncedAtEpochMs = lastSyncedAtEpochMs,
                fetchedAtEpochMs = lastSyncedAtEpochMs,
            ),
        )
        assertEquals(
            listOf(midSyncHeld),
            mergeFetchedPlayback(
                fetched = listOf(fetchedRow),
                held = listOf(midSyncHeld),
                lastSyncedAtEpochMs = lastSyncedAtEpochMs,
                fetchedAtEpochMs = lastSyncedAtEpochMs,
            ),
        )
    }

    private fun episodePlayback(
        episode: Int,
        progress: Double = 30.0,
        pausedAt: String,
    ): SimklPlaybackSession {
        val media = SimklMedia(
            title = "The Walking Dead",
            year = 2010,
            ids = mapOf(
                "simkl" to JsonPrimitive(2090L),
                "imdb" to JsonPrimitive("tt1520211"),
            ),
        )
        return SimklPlaybackSession(
            progress = progress,
            pausedAt = pausedAt,
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = episode),
            show = media,
        )
    }
}
