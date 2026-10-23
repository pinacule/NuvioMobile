package com.nuvio.app.features.library

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryOfflineFilterTest {

    @Test
    fun `movie with a download is shown`() {
        val sections = listOf(
            section("movie", movieItem("movie-a"), movieItem("movie-b")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(movieDownload("movie-a")),
        )

        assertEquals(listOf("movie-a"), filtered.single().items.map { it.id })
    }

    @Test
    fun `series with one downloaded episode is shown`() {
        val sections = listOf(
            section("series", seriesItem("show-a"), seriesItem("show-b")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(episodeDownload("show-a", season = 1, episode = 3)),
        )

        assertEquals(listOf("show-a"), filtered.single().items.map { it.id })
    }

    @Test
    fun `series with no downloads is hidden`() {
        val sections = listOf(
            section("series", seriesItem("show-a")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(movieDownload("movie-a")),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `empty verified downloads hides everything`() {
        // A completed record whose file was deleted outside the app never
        // reaches this filter: playableLocalDownloads() drops it, so the
        // verified input is empty and nothing may stay visible.
        val sections = listOf(
            section("series", seriesItem("show-a")),
            section("movie", movieItem("movie-a")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = emptyList(),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `episode download does not keep a movie with the same id`() {
        val sections = listOf(
            section("movie", movieItem("shared-id")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(episodeDownload("shared-id", season = 1, episode = 1)),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `movie download does not keep a series with the same id`() {
        val sections = listOf(
            section("series", seriesItem("shared-id")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(movieDownload("shared-id")),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `partially coordinated download validates neither kind`() {
        val sections = listOf(
            section("series", seriesItem("show-a")),
            section("movie", movieItem("show-a")),
        )
        val seasonOnly = episodeDownload("show-a", season = 1, episode = 1)
            .copy(episodeNumber = null)

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(seasonOnly),
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `sections left empty by the filter are dropped and order is preserved`() {
        val sections = listOf(
            section("movie", movieItem("movie-a")),
            section("series", seriesItem("show-a"), seriesItem("show-b")),
            section("movie2", movieItem("movie-b")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(
                episodeDownload("show-b", season = 2, episode = 1),
                movieDownload("movie-b"),
            ),
        )

        assertEquals(listOf("series", "movie2"), filtered.map { it.type })
        assertEquals(listOf("show-b"), filtered[0].items.map { it.id })
        assertEquals(listOf("movie-b"), filtered[1].items.map { it.id })
    }

    @Test
    fun `ids are matched trimmed on both sides`() {
        val sections = listOf(
            section("series", seriesItem(" show-a ")),
        )

        val filtered = filterLibrarySectionsForOfflineHome(
            sections = sections,
            downloads = listOf(episodeDownload("show-a", season = 1, episode = 1)),
        )

        assertEquals(1, filtered.single().items.size)
    }

    private fun section(type: String, vararg items: LibraryItem) = LibrarySection(
        type = type,
        displayTitle = type,
        items = items.toList(),
    )

    private fun seriesItem(id: String) = LibraryItem(
        id = id,
        type = "series",
        name = "Show $id",
        savedAtEpochMs = 0L,
    )

    private fun movieItem(id: String) = LibraryItem(
        id = id,
        type = "movie",
        name = "Movie $id",
        savedAtEpochMs = 0L,
    )

    private fun episodeDownload(
        showId: String,
        season: Int,
        episode: Int,
    ) = DownloadItem(
        id = "$showId-$season-$episode",
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:$season:$episode",
        title = "Show $showId",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.test/$showId/$season/$episode",
        localFileUri = "file:///downloads/$showId-$season-$episode.mkv",
        fileName = "$showId-$season-$episode.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private fun movieDownload(movieId: String) = DownloadItem(
        id = movieId,
        contentType = "movie",
        parentMetaId = movieId,
        parentMetaType = "movie",
        videoId = movieId,
        title = "Movie $movieId",
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.test/$movieId",
        localFileUri = "file:///downloads/$movieId.mkv",
        fileName = "$movieId.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
