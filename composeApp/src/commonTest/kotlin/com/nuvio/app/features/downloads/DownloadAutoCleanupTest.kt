package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadAutoCleanupTest {

    @Test
    fun userExample_startingEp3DeletesEp1Only() {
        val items = listOf(episode(1), episode(2), episode(3))
        val toDelete = episodesToAutoCleanup(items, started = episode(3))
        assertEquals(listOf(1), toDelete.map { it.episodeNumber })
    }

    @Test
    fun startingEp2DeletesNothing() {
        val items = listOf(episode(1), episode(2))
        assertTrue(episodesToAutoCleanup(items, started = episode(2)).isEmpty())
    }

    @Test
    fun startingEp1DeletesNothing() {
        val items = listOf(episode(1))
        assertTrue(episodesToAutoCleanup(items, started = episode(1)).isEmpty())
    }

    @Test
    fun startingEp5DeletesEps1To3AndKeepsEp4() {
        val items = (1..5).map { episode(it) }
        val toDelete = episodesToAutoCleanup(items, started = episode(5))
        assertEquals(listOf(1, 2, 3), toDelete.map { it.episodeNumber })
    }

    @Test
    fun gapsInDownloadedEpisodesAreFine() {
        val items = listOf(episode(1), episode(3), episode(5))
        val toDelete = episodesToAutoCleanup(items, started = episode(5))
        assertEquals(listOf(1, 3), toDelete.map { it.episodeNumber })
    }

    @Test
    fun nonCompletedItemsAreNeverDeleted() {
        val items = listOf(
            episode(1, status = DownloadStatus.Downloading),
            episode(2, status = DownloadStatus.Paused),
            episode(3, status = DownloadStatus.Failed),
            episode(4),
            episode(5),
            episode(6),
        )
        val toDelete = episodesToAutoCleanup(items, started = episode(6))
        assertEquals(listOf(4), toDelete.map { it.episodeNumber })
    }

    @Test
    fun otherSeasonsAndSeriesAreUntouched() {
        val items = listOf(
            episode(1),
            episode(2),
            episode(3),
            episode(1, season = 2),
            episode(2, season = 2),
            episode(1, parentMetaId = "tt-other"),
            episode(2, parentMetaId = "tt-other"),
        )
        val toDelete = episodesToAutoCleanup(items, started = episode(3))
        assertEquals(listOf(episode(1).id), toDelete.map { it.id })
    }

    @Test
    fun movieStartDeletesNothing() {
        val movie = episode(1).copy(
            id = "movie",
            seasonNumber = null,
            episodeNumber = null,
        )
        val items = listOf(episode(1), episode(2), episode(3), movie)
        assertTrue(episodesToAutoCleanup(items, started = movie).isEmpty())
    }

    @Test
    fun startedItemThatIsNotCompletedDeletesNothing() {
        val items = (1..5).map { episode(it) }
        val started = episode(5, status = DownloadStatus.Downloading)
        assertTrue(episodesToAutoCleanup(items, started = started).isEmpty())
    }

    @Test
    fun itemsWithoutEpisodeNumbersAreIgnored() {
        val noEpisodeNumber = episode(1).copy(id = "no-ep", episodeNumber = null)
        val items = listOf(noEpisodeNumber, episode(1), episode(2), episode(3))
        val toDelete = episodesToAutoCleanup(items, started = episode(3))
        assertEquals(listOf(1), toDelete.map { it.episodeNumber })
    }

    private fun episode(
        number: Int,
        season: Int = 1,
        parentMetaId: String = "tt-series",
        status: DownloadStatus = DownloadStatus.Completed,
    ): DownloadItem = DownloadItem(
        id = "$parentMetaId-s$season-e$number",
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "$parentMetaId:${season}:$number",
        title = "Series",
        seasonNumber = season,
        episodeNumber = number,
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.com/s${season}e$number.mp4",
        localFileUri = "file:///downloads/s${season}e$number.mp4",
        fileName = "Series S0${season}E0$number.mp4",
        status = status,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
