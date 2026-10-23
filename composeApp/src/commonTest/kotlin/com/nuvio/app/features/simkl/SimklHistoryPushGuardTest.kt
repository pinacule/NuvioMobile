package com.nuvio.app.features.simkl

import com.nuvio.app.features.watched.WatchedItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A show-level mark makes Simkl mark every episode of a series watched, so nothing but a film may
 * leave the app without episode coordinates.
 */
class SimklHistoryPushGuardTest {
    private fun mark(
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ) = WatchedItem(
        id = "tt2861424",
        type = type,
        name = "Rick and Morty",
        season = season,
        episode = episode,
        markedAtEpochMs = 1_700_000_000_000,
    )

    @Test
    fun `a mark without an episode never reaches Simkl for a series`() {
        assertTrue(simklHistoryPushItems(listOf(mark("series"))).isEmpty())
        assertTrue(simklHistoryPushItems(listOf(mark("show"))).isEmpty())
        assertTrue(simklHistoryPushItems(listOf(mark("tv"))).isEmpty())
        assertTrue(simklHistoryPushItems(listOf(mark("anime"))).isEmpty())
        // An unexpected type must fail safe as well: dropping a suspicious mark costs nothing,
        // pushing it can wipe a whole series.
        assertTrue(simklHistoryPushItems(listOf(mark("documentary-series"))).isEmpty())
    }

    @Test
    fun `episode marks always reach Simkl`() {
        val pushed = simklHistoryPushItems(listOf(mark("series", season = 1, episode = 1)))
        assertEquals(1, pushed.size)
        assertEquals(1, pushed.single().season)
        assertEquals(1, pushed.single().episode)
    }

    @Test
    fun `films need no episode`() {
        assertEquals(1, simklHistoryPushItems(listOf(mark("movie"))).size)
        assertEquals(1, simklHistoryPushItems(listOf(mark("film"))).size)
        assertEquals(1, simklHistoryPushItems(listOf(mark("Movie"))).size)
    }

    @Test
    fun `the episodes of a whole-series action still travel`() {
        val pushed = simklHistoryPushItems(
            listOf(
                mark("series"),
                mark("series", season = 1, episode = 1),
                mark("series", season = 9, episode = 10),
            ),
        )

        assertEquals(listOf(1 to 1, 9 to 10), pushed.map { it.season to it.episode })
    }

    @Test
    fun `an anime film proven by the snapshot is rescued for pushing`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.ANIME,
                    animeType = "movie",
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
        val animeFilm = WatchedItem(
            id = "mal:555",
            type = "anime",
            name = "Anime Film",
            markedAtEpochMs = 1_700_000_000_000,
        )

        // The pure guard still drops it; only the snapshot rescue lets it through.
        assertTrue(simklHistoryPushItems(listOf(animeFilm)).isEmpty())
        assertEquals(1, simklAnimeMoviePushItems(listOf(animeFilm), snapshot).size)
    }

    @Test
    fun `an anime series in the snapshot is not rescued for pushing`() {
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
        val animeSeries = WatchedItem(
            id = "mal:777",
            type = "anime",
            name = "Anime Series",
            markedAtEpochMs = 1_700_000_000_000,
        )

        assertTrue(simklAnimeMoviePushItems(listOf(animeSeries), snapshot).isEmpty())
    }

    @Test
    fun `an anime mark with no snapshot entry is not rescued for pushing`() {
        val animeFilm = WatchedItem(
            id = "mal:555",
            type = "anime",
            name = "Anime Film",
            markedAtEpochMs = 1_700_000_000_000,
        )

        assertTrue(simklAnimeMoviePushItems(listOf(animeFilm), SimklSyncSnapshot()).isEmpty())
    }

    @Test
    fun `a mixed push keeps the films and the episodes`() {
        val pushed = simklHistoryPushItems(
            listOf(
                mark("movie"),
                mark("series"),
                mark("series", season = 2, episode = 6),
            ),
        )

        assertEquals(2, pushed.size)
        assertTrue(pushed.any { it.type == "movie" })
        assertTrue(pushed.any { it.season == 2 && it.episode == 6 })
    }
}
