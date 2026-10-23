package com.nuvio.app.features.home

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineHomeTest {

    // region mode resolution

    @Test
    fun `default is active offline and on cellular only`() {
        assertTrue(offlineHomeDefaultActive(OfflineHomeTransport.Offline))
        assertTrue(offlineHomeDefaultActive(OfflineHomeTransport.Cellular))
        assertFalse(offlineHomeDefaultActive(OfflineHomeTransport.Wifi))
        assertFalse(offlineHomeDefaultActive(OfflineHomeTransport.Unknown))
    }

    @Test
    fun `manual override wins over the connectivity default`() {
        assertTrue(resolveOfflineHomeActive(OfflineHomeTransport.Wifi, manualOverride = true))
        assertFalse(resolveOfflineHomeActive(OfflineHomeTransport.Cellular, manualOverride = false))
        assertFalse(resolveOfflineHomeActive(OfflineHomeTransport.Offline, manualOverride = false))
        assertTrue(resolveOfflineHomeActive(OfflineHomeTransport.Cellular, manualOverride = null))
        assertFalse(resolveOfflineHomeActive(OfflineHomeTransport.Wifi, manualOverride = null))
    }

    @Test
    fun `override survives re-sampling but clears when the transport class changes`() {
        assertEquals(
            false,
            nextOfflineHomeOverride(false, OfflineHomeTransport.Cellular, OfflineHomeTransport.Cellular),
        )
        assertNull(
            nextOfflineHomeOverride(false, OfflineHomeTransport.Cellular, OfflineHomeTransport.Wifi),
        )
        assertNull(
            nextOfflineHomeOverride(true, OfflineHomeTransport.Wifi, OfflineHomeTransport.Offline),
        )
        assertNull(
            nextOfflineHomeOverride(null, OfflineHomeTransport.Wifi, OfflineHomeTransport.Cellular),
        )
    }

    // endregion

    // region transport classification

    @Test
    fun `wifi radio wins over a failed probe so the override survives`() {
        assertEquals(
            OfflineHomeTransport.Wifi,
            classifyOfflineHomeTransport(
                isWifi = true,
                isCellular = false,
                condition = NetworkCondition.NoInternet,
            ),
        )
    }

    @Test
    fun `cellular radio wins over a failed probe so the override survives`() {
        assertEquals(
            OfflineHomeTransport.Cellular,
            classifyOfflineHomeTransport(
                isWifi = false,
                isCellular = true,
                condition = NetworkCondition.NoInternet,
            ),
        )
    }

    @Test
    fun `no radio and a failed probe is offline`() {
        assertEquals(
            OfflineHomeTransport.Offline,
            classifyOfflineHomeTransport(
                isWifi = false,
                isCellular = false,
                condition = NetworkCondition.NoInternet,
            ),
        )
    }

    @Test
    fun `no radio and servers unreachable is unknown not offline`() {
        // ServersUnreachable means the internet probe succeeded and only the
        // backend failed — that is not Offline.
        assertEquals(
            OfflineHomeTransport.Unknown,
            classifyOfflineHomeTransport(
                isWifi = false,
                isCellular = false,
                condition = NetworkCondition.ServersUnreachable,
            ),
        )
    }

    @Test
    fun `no radio and a healthy probe is unknown`() {
        assertEquals(
            OfflineHomeTransport.Unknown,
            classifyOfflineHomeTransport(
                isWifi = false,
                isCellular = false,
                condition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `wifi radio with a healthy probe is wifi`() {
        assertEquals(
            OfflineHomeTransport.Wifi,
            classifyOfflineHomeTransport(
                isWifi = true,
                isCellular = false,
                condition = NetworkCondition.Online,
            ),
        )
    }

    @Test
    fun `cellular radio with a healthy probe is cellular`() {
        assertEquals(
            OfflineHomeTransport.Cellular,
            classifyOfflineHomeTransport(
                isWifi = false,
                isCellular = true,
                condition = NetworkCondition.Online,
            ),
        )
    }

    // endregion

    // region transport confirmation

    @Test
    fun `confirmation commits the startup sample immediately`() {
        val state = confirmedTransport(ConfirmedTransportState(), OfflineHomeTransport.Cellular)

        assertEquals(OfflineHomeTransport.Cellular, state.confirmed)
        assertNull(state.pendingClass)
    }

    @Test
    fun `confirmation single-sample flap keeps class and override`() {
        var state = ConfirmedTransportState(confirmed = OfflineHomeTransport.Wifi)

        state = confirmedTransport(state, OfflineHomeTransport.Offline)

        assertEquals(OfflineHomeTransport.Wifi, state.confirmed)
        // Feeding the confirmed (unchanged) class through the unchanged
        // override rule keeps the user's manual override.
        assertEquals(
            true,
            nextOfflineHomeOverride(true, OfflineHomeTransport.Wifi, state.confirmed!!),
        )
        // Returning to the confirmed class clears the pending flap.
        state = confirmedTransport(state, OfflineHomeTransport.Wifi)
        assertNull(state.pendingClass)
        assertEquals(OfflineHomeTransport.Wifi, state.confirmed)
    }

    @Test
    fun `confirmation commits after two consecutive samples of the new class`() {
        var state = ConfirmedTransportState(confirmed = OfflineHomeTransport.Wifi)

        state = confirmedTransport(state, OfflineHomeTransport.Cellular)
        assertEquals(OfflineHomeTransport.Wifi, state.confirmed)
        state = confirmedTransport(state, OfflineHomeTransport.Cellular)

        assertEquals(OfflineHomeTransport.Cellular, state.confirmed)
        assertNull(state.pendingClass)
        // A confirmed change is what clears the override.
        assertNull(
            nextOfflineHomeOverride(true, OfflineHomeTransport.Wifi, state.confirmed!!),
        )
    }

    @Test
    fun `confirmation unknown blips never commit and break a pending run`() {
        var state = ConfirmedTransportState(confirmed = OfflineHomeTransport.Wifi)

        state = confirmedTransport(state, OfflineHomeTransport.Unknown)
        state = confirmedTransport(state, OfflineHomeTransport.Unknown)
        assertEquals(OfflineHomeTransport.Wifi, state.confirmed)

        // An Unknown between two samples of a new class breaks consecutiveness:
        // the second Cellular sample starts a fresh pending run, not a commit.
        state = confirmedTransport(state, OfflineHomeTransport.Cellular)
        state = confirmedTransport(state, OfflineHomeTransport.Unknown)
        state = confirmedTransport(state, OfflineHomeTransport.Cellular)
        assertEquals(OfflineHomeTransport.Wifi, state.confirmed)
        state = confirmedTransport(state, OfflineHomeTransport.Cellular)
        assertEquals(OfflineHomeTransport.Cellular, state.confirmed)
    }

    // endregion

    // region downloads-only Continue Watching

    @Test
    fun `cw filter keeps only items whose exact video is downloaded`() {
        val downloads = listOf(
            episodeDownload(showId = "show-a", season = 1, episode = 3),
            movieDownload(movieId = "movie-a"),
        )
        val resumeDownloaded = episodeCwItem(showId = "show-a", season = 1, episode = 3)
        val resumeNotDownloaded = episodeCwItem(showId = "show-a", season = 1, episode = 4)
        val nextUpDownloaded = episodeCwItem(showId = "show-a", season = 1, episode = 3, isNextUp = true)
        val movieDownloaded = movieCwItem(movieId = "movie-a")
        val movieNotDownloaded = movieCwItem(movieId = "movie-b")
        val otherShow = episodeCwItem(showId = "show-b", season = 1, episode = 3)

        val filtered = filterContinueWatchingForOfflineHome(
            items = listOf(
                resumeDownloaded,
                resumeNotDownloaded,
                nextUpDownloaded,
                movieDownloaded,
                movieNotDownloaded,
                otherShow,
            ),
            downloads = downloads,
        )

        assertEquals(listOf(resumeDownloaded, nextUpDownloaded, movieDownloaded), filtered)
    }

    @Test
    fun `cw filter keeps disk-verified download whose stored uri is blank`() {
        // The caller passes playableLocalDownloads() (disk-verified). A file
        // can resolve by name fallback with a blank stored URI; the old
        // isPlayable re-filter (Completed + non-blank stored URI) dropped it.
        // The filter now trusts the caller's verified list.
        val verifiedByFallback = episodeDownload(showId = "show-a", season = 1, episode = 3)
            .copy(localFileUri = null)
        val item = episodeCwItem(showId = "show-a", season = 1, episode = 3)

        assertEquals(
            listOf(item),
            filterContinueWatchingForOfflineHome(listOf(item), listOf(verifiedByFallback)),
        )
        assertTrue(filterContinueWatchingForOfflineHome(listOf(item), emptyList()).isEmpty())
    }

    @Test
    fun `cw filter excludes series items with missing coordinates even when the show is downloaded`() {
        val downloads = listOf(episodeDownload(showId = "show-a", season = 1, episode = 3))
        val missingEpisode = episodeCwItem(showId = "show-a", season = 1, episode = 3)
            .copy(episodeNumber = null)
        val missingSeason = episodeCwItem(showId = "show-a", season = 1, episode = 3)
            .copy(seasonNumber = null)
        val missingBoth = episodeCwItem(showId = "show-a", season = 1, episode = 3)
            .copy(seasonNumber = null, episodeNumber = null)

        val filtered = filterContinueWatchingForOfflineHome(
            items = listOf(missingEpisode, missingSeason, missingBoth),
            downloads = downloads,
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `cw filter keeps a movie item matched by its own movie download`() {
        val downloads = listOf(movieDownload(movieId = "movie-a"))

        val filtered = filterContinueWatchingForOfflineHome(
            items = listOf(movieCwItem(movieId = "movie-a")),
            downloads = downloads,
        )

        assertEquals(listOf("movie-a"), filtered.map { it.parentMetaId })
    }

    @Test
    fun `cw filter excludes movie item when only an episode carries its id`() {
        // An episode record whose parent id equals the movie's id is not a
        // download of the movie.
        val downloads = listOf(episodeDownload(showId = "movie-a", season = 1, episode = 1))

        val filtered = filterContinueWatchingForOfflineHome(
            items = listOf(movieCwItem(movieId = "movie-a")),
            downloads = downloads,
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `cw filter excludes movie item when coordinate-less download is series typed`() {
        // A coordinate-less download typed as series (not movie) sharing the
        // movie's id must not validate the movie CW entry.
        val downloads = listOf(
            movieDownload(movieId = "movie-a").copy(parentMetaType = "series", contentType = "series"),
        )

        val filtered = filterContinueWatchingForOfflineHome(
            items = listOf(movieCwItem(movieId = "movie-a")),
            downloads = downloads,
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `cw filter preserves resume position of kept items`() {
        val downloads = listOf(episodeDownload(showId = "show-a", season = 2, episode = 5))
        val item = episodeCwItem(showId = "show-a", season = 2, episode = 5)
            .copy(resumePositionMs = 123_456L, progressFraction = 0.5f)

        val filtered = filterContinueWatchingForOfflineHome(listOf(item), downloads)

        assertEquals(123_456L, filtered.single().resumePositionMs)
    }

    // endregion

    // region tiles

    @Test
    fun `tiles group downloads per content and prefer the continue watching target`() {
        val downloads = listOf(
            episodeDownload(showId = "show-a", season = 1, episode = 1, updatedAt = 10L),
            episodeDownload(showId = "show-a", season = 1, episode = 2, updatedAt = 20L),
            episodeDownload(showId = "show-a", season = 1, episode = 3, updatedAt = 30L),
            movieDownload(movieId = "movie-a", updatedAt = 40L),
        )
        val cw = listOf(episodeCwItem(showId = "show-a", season = 1, episode = 3))

        val tiles = buildOfflineHomeDownloadTiles(downloads, cw)

        assertEquals(2, tiles.size)
        val showTile = tiles.first { it.parentMetaId == "show-a" }
        assertEquals(3, showTile.download.episodeNumber)
        val movieTile = tiles.first { it.parentMetaId == "movie-a" }
        assertEquals("movie-a", movieTile.download.parentMetaId)
    }

    @Test
    fun `tiles match continue watching by id and type not id alone`() {
        // A movie and a series share the same parent id. The movie CW entry
        // is listed first; keying CW by id alone would give the series tile
        // the movie entry (no coordinates) and lose its S1E2 target.
        val downloads = listOf(
            episodeDownload(showId = "shared", season = 1, episode = 1, updatedAt = 10L),
            episodeDownload(showId = "shared", season = 1, episode = 2, updatedAt = 20L),
            movieDownload(movieId = "shared", updatedAt = 30L),
        )
        val cw = listOf(
            movieCwItem(movieId = "shared"),
            episodeCwItem(showId = "shared", season = 1, episode = 2),
        )

        val tiles = buildOfflineHomeDownloadTiles(downloads, cw)

        assertEquals(2, tiles.size)
        val showTile = tiles.first { it.parentMetaType == "series" }
        assertEquals(2, showTile.download.episodeNumber)
        val movieTile = tiles.first { it.parentMetaType == "movie" }
        assertEquals("shared", movieTile.download.parentMetaId)
        assertFalse(movieTile.download.isEpisode)
    }

    @Test
    fun `tiles fall back to the first episode in season order without cw`() {
        val downloads = listOf(
            episodeDownload(showId = "show-a", season = 1, episode = 2, updatedAt = 20L),
            episodeDownload(showId = "show-a", season = 1, episode = 1, updatedAt = 10L),
        )

        val tiles = buildOfflineHomeDownloadTiles(downloads, emptyList())

        assertEquals(1, tiles.single().download.episodeNumber)
    }

    @Test
    fun `tiles collapse re-downloads of the same episode to the newest copy`() {
        val downloads = listOf(
            episodeDownload(showId = "show-a", season = 1, episode = 1, updatedAt = 10L)
                .copy(id = "old", localFileUri = "file:///old.mkv"),
            episodeDownload(showId = "show-a", season = 1, episode = 1, updatedAt = 99L)
                .copy(id = "new", localFileUri = "file:///new.mkv"),
        )

        val tiles = buildOfflineHomeDownloadTiles(downloads, emptyList())

        assertEquals("new", tiles.single().download.id)
    }

    @Test
    fun `tiles are ordered by most recent download activity`() {
        // Unplayable filtering is the caller's job (playableLocalDownloads);
        // this builder no longer re-filters with isPlayable.
        val downloads = listOf(
            movieDownload(movieId = "movie-old", updatedAt = 10L),
            movieDownload(movieId = "movie-new", updatedAt = 90L),
        )

        val tiles = buildOfflineHomeDownloadTiles(downloads, emptyList())

        assertEquals(listOf("movie-new", "movie-old"), tiles.map { it.parentMetaId })
    }

    @Test
    fun `tiles keep disk-verified download whose stored uri is blank`() {
        val downloads = listOf(
            movieDownload(movieId = "movie-a").copy(localFileUri = null),
        )

        val tiles = buildOfflineHomeDownloadTiles(downloads, emptyList())

        assertEquals(listOf("movie-a"), tiles.map { it.parentMetaId })
    }

    @Test
    fun `tiles are empty when nothing is downloaded`() {
        assertTrue(buildOfflineHomeDownloadTiles(emptyList(), emptyList()).isEmpty())
    }

    // endregion

    private fun episodeDownload(
        showId: String,
        season: Int,
        episode: Int,
        updatedAt: Long = 1L,
    ) = DownloadItem(
        id = "$showId-$season-$episode",
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:$season:$episode",
        title = "Show $showId",
        poster = "https://example.test/$showId.jpg",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.test/$showId/$season/$episode",
        localFileUri = "file:///downloads/$showId-$season-$episode.mkv",
        fileName = "$showId-$season-$episode.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = updatedAt,
        updatedAtEpochMs = updatedAt,
    )

    private fun movieDownload(
        movieId: String,
        updatedAt: Long = 1L,
    ) = DownloadItem(
        id = movieId,
        contentType = "movie",
        parentMetaId = movieId,
        parentMetaType = "movie",
        videoId = movieId,
        title = "Movie $movieId",
        poster = "https://example.test/$movieId.jpg",
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "https://example.test/$movieId",
        localFileUri = "file:///downloads/$movieId.mkv",
        fileName = "$movieId.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = updatedAt,
        updatedAtEpochMs = updatedAt,
    )

    private fun episodeCwItem(
        showId: String,
        season: Int,
        episode: Int,
        isNextUp: Boolean = false,
    ) = ContinueWatchingItem(
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:$season:$episode",
        title = "Show $showId",
        subtitle = "S${season}E$episode",
        imageUrl = null,
        seasonNumber = season,
        episodeNumber = episode,
        isNextUp = isNextUp,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = 0f,
    )

    private fun movieCwItem(movieId: String) = ContinueWatchingItem(
        parentMetaId = movieId,
        parentMetaType = "movie",
        videoId = movieId,
        title = "Movie $movieId",
        subtitle = "",
        imageUrl = null,
        resumePositionMs = 60_000L,
        durationMs = 3_600_000L,
        progressFraction = 0.25f,
    )
}
