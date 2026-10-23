package com.nuvio.app.features.simkl

import com.nuvio.app.features.watched.WatchedItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mirror of [SimklHistoryPushGuardTest] for removals: an episode may always be removed,
 * and a season-less item may be removed only when it is a film. A season-less series mark
 * names no episode, so it must never become a show-level history removal — and a movie
 * unmark must not be silently dropped just because it carries no episode coordinates
 * (that was the bug: `delete()` filtered to episodes only and returned, so the next
 * snapshot pull resurrected the movie as watched).
 */
class SimklHistoryRemovalGuardTest {
    private fun mark(
        id: String = "tt2861424",
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ) = WatchedItem(
        id = id,
        type = type,
        name = "Item $id",
        season = season,
        episode = episode,
        markedAtEpochMs = 1_700_000_000_000,
    )

    @Test
    fun `a movie unmark reaches the removal set without any snapshot entry`() {
        val removals = simklHistoryRemovalItems(
            listOf(mark(type = "movie"), mark(type = "film")),
            SimklSyncSnapshot(),
        )

        assertEquals(2, removals.size)
    }

    @Test
    fun `episode unmarks always reach the removal set`() {
        val removals = simklHistoryRemovalItems(
            listOf(mark(type = "series", season = 2, episode = 6)),
            SimklSyncSnapshot(),
        )

        assertEquals(1, removals.size)
    }

    @Test
    fun `a whole-series unmark stays out of the removal set`() {
        val removals = simklHistoryRemovalItems(
            listOf(mark(type = "series"), mark(type = "anime")),
            SimklSyncSnapshot(),
        )

        assertTrue(removals.isEmpty())
    }

    @Test
    fun `a mixed delete keeps the movie and the episodes but not the series mark`() {
        val removals = simklHistoryRemovalItems(
            listOf(
                mark(id = "tt0111161", type = "movie"),
                mark(type = "series"),
                mark(type = "series", season = 1, episode = 3),
            ),
            SimklSyncSnapshot(),
        )

        assertEquals(2, removals.size)
        assertTrue(removals.any { it.type == "movie" })
        assertTrue(removals.any { it.season == 1 && it.episode == 3 })
    }

    @Test
    fun `an anime film in the snapshot is removed as a movie`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.ANIME,
                    animeType = "movie",
                    lastWatchedAt = "2023-11-14T23:00:00Z",
                    movie = SimklMedia(
                        title = "Anime Film",
                        year = 2020,
                        ids = buildJsonObject {
                            put("simkl", 70001)
                            put("mal", 555)
                        },
                    ),
                ),
            ),
        )

        val removals = simklHistoryRemovalItems(
            listOf(mark(id = "mal:555", type = "anime")),
            snapshot,
        )

        assertEquals(1, removals.size)
    }

    @Test
    fun `an anime series in the snapshot is not removed as a movie`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.ANIME,
                    animeType = "tv",
                    show = SimklMedia(
                        title = "Anime Series",
                        year = 2020,
                        ids = buildJsonObject {
                            put("simkl", 70002)
                            put("mal", 777)
                        },
                    ),
                ),
            ),
        )

        val removals = simklHistoryRemovalItems(
            listOf(mark(id = "mal:777", type = "anime")),
            snapshot,
        )

        assertTrue(removals.isEmpty())
    }
}
