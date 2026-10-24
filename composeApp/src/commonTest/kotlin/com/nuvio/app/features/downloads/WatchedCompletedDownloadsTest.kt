package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchedCompletedDownloadsTest {

    @Test
    fun watchedEpisodeIsSelected() {
        val episode = item("episode-1", season = 1, episode = 1)

        assertEquals(
            listOf(episode),
            watchedCompletedDownloads(
                items = listOf(episode),
                watchStates = mapOf(episode.id to DownloadWatchState(progressEntry = null, isWatched = true)),
            ),
        )
    }

    @Test
    fun unwatchedEpisodeIsNotSelected() {
        val episode = item("episode-1", season = 1, episode = 1)

        assertEquals(
            emptyList(),
            watchedCompletedDownloads(
                items = listOf(episode),
                watchStates = mapOf(episode.id to DownloadWatchState(progressEntry = null, isWatched = false)),
            ),
        )
    }

    @Test
    fun partiallyWatchedEpisodeIsNotSelected() {
        val episode = item("episode-1", season = 1, episode = 1)
        val progressEntry = WatchProgressEntry(
            contentType = "series",
            parentMetaId = episode.parentMetaId,
            parentMetaType = episode.parentMetaType,
            videoId = episode.videoId,
            title = episode.title,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            lastPositionMs = 300_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = 1L,
            isCompleted = false,
            progressPercent = 30f,
        )

        assertEquals(
            emptyList(),
            watchedCompletedDownloads(
                items = listOf(episode),
                watchStates = mapOf(
                    episode.id to DownloadWatchState(progressEntry = progressEntry, isWatched = false),
                ),
            ),
        )
    }

    @Test
    fun watchedMovieIsSelected() {
        val movie = item("movie-1")

        assertEquals(
            listOf(movie),
            watchedCompletedDownloads(
                items = listOf(movie),
                watchStates = mapOf(movie.id to DownloadWatchState(progressEntry = null, isWatched = true)),
            ),
        )
    }

    @Test
    fun activeDownloadIsNotSelected() {
        val activeEpisode = item(
            id = "episode-1",
            season = 1,
            episode = 1,
            status = DownloadStatus.Downloading,
        )

        assertEquals(
            emptyList(),
            watchedCompletedDownloads(
                items = listOf(activeEpisode),
                watchStates = mapOf(
                    activeEpisode.id to DownloadWatchState(progressEntry = null, isWatched = true),
                ),
            ),
        )
    }

    private fun item(
        id: String,
        season: Int? = null,
        episode: Int? = null,
        status: DownloadStatus = DownloadStatus.Completed,
    ) = DownloadItem(
        id = id,
        contentType = if (season != null && episode != null) "series" else "movie",
        parentMetaId = "parent-$id",
        parentMetaType = if (season != null && episode != null) "series" else "movie",
        videoId = "video-$id",
        title = "Title $id",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "Torrentio",
        sourceUrl = "https://example.com/$id.mkv",
        fileName = "$id.mkv",
        status = status,
        downloadedBytes = 100L,
        totalBytes = 100L,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
