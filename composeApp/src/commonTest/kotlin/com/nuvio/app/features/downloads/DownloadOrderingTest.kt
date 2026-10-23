package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadOrderingTest {

    private fun item(
        id: String,
        title: String,
        parentMetaId: String,
        season: Int? = null,
        episode: Int? = null,
    ) = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "video-$id",
        title = title,
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "Torrentio",
        sourceUrl = "https://example.com/$id.mkv",
        fileName = "$id.mkv",
        status = DownloadStatus.Paused,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    @Test
    fun orderingIsScopedPerSeries() {
        val alphaE5 = item("a5", "Alpha", "tt-alpha", season = 1, episode = 5)
        val alphaE1 = item("a1", "Alpha", "tt-alpha", season = 1, episode = 1)
        val betaE1 = item("b1", "Beta", "tt-beta", season = 1, episode = 1)
        val movie = item("m1", "Movie", "tt-movie")

        // Per-series scope: Alpha's episodes stay together in episode order,
        // then Beta's — Beta's S01E01 no longer jumps ahead of Alpha's S01E05
        // the way the old global season/episode order allowed. The movie
        // sorts by its own title instead of behind every episode.
        assertEquals(
            listOf(alphaE1, alphaE5, betaE1, movie),
            listOf(alphaE5, betaE1, movie, alphaE1).sortedForSeriesDownloads(),
        )
    }

    @Test
    fun sameTitleDifferentParentsDoNotInterleave() {
        // Two series sharing a display title still group by parent id.
        val first = item("x2", "Show", "tt-2", season = 1, episode = 2)
        val second = item("y1", "Show", "tt-1", season = 1, episode = 1)
        val firstE1 = item("x1", "Show", "tt-2", season = 1, episode = 1)

        assertEquals(
            listOf(second, firstE1, first),
            listOf(first, second, firstE1).sortedForSeriesDownloads(),
        )
    }
}
