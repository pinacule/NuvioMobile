package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeDownloadPlannerTest {

    private fun video(id: String, season: Int?, episode: Int?, available: Boolean = true) =
        MetaVideo(id = id, title = "Ep $episode", season = season, episode = episode, available = available)

    private fun entry(season: Int, episode: Int, completed: Boolean, updated: Long) =
        WatchProgressEntry(
            contentType = "series",
            parentMetaId = "show1",
            parentMetaType = "series",
            videoId = "show1:$season:$episode",
            title = "Show",
            lastPositionMs = 0L,
            durationMs = 1000L,
            lastUpdatedEpochMs = updated,
            seasonNumber = season,
            episodeNumber = episode,
            isCompleted = completed,
        )

    private fun stream(name: String, torrent: Boolean = true) = StreamItem(
        name = name,
        infoHash = if (torrent) "a".repeat(40) else null,
        url = if (torrent) null else "https://cdn.example/v.mp4",
        addonName = "Torrentio",
        addonId = "addon:torrentio",
    )

    @Test
    fun anchorPicksMostRecentlyTouchedEpisode() {
        val entries = listOf(
            entry(1, 1, completed = true, updated = 100L),
            entry(1, 3, completed = false, updated = 300L),
            entry(1, 2, completed = true, updated = 200L),
        )
        val anchor = EpisodeDownloadPlanner.latestAnchor(entries, "show1")
        assertEquals(EpisodeAnchor(season = 1, episode = 3, isCompleted = false, lastUpdatedEpochMs = 300L), anchor)
    }

    @Test
    fun anchorIgnoresOtherShowsAndNullWhenNeverStarted() {
        val entries = listOf(entry(2, 1, completed = false, updated = 100L).copy(parentMetaId = "other"))
        assertNull(EpisodeDownloadPlanner.latestAnchor(entries, "show1"))
        assertNull(EpisodeDownloadPlanner.latestAnchor(emptyList(), "show1"))
    }

    @Test
    fun startVideoIsInProgressEpisode() {
        val videos = listOf(video("s1e1", 1, 1), video("s1e2", 1, 2), video("s1e3", 1, 3))
        val start = EpisodeDownloadPlanner.resolveStartVideo(
            videos,
            EpisodeAnchor(1, 2, isCompleted = false, lastUpdatedEpochMs = 1L),
        )
        assertEquals("s1e2", start?.id)
    }

    @Test
    fun startVideoSkipsCompletedEpisode() {
        val videos = listOf(video("s1e1", 1, 1), video("s1e2", 1, 2), video("s1e3", 1, 3))
        val start = EpisodeDownloadPlanner.resolveStartVideo(
            videos,
            EpisodeAnchor(1, 2, isCompleted = true, lastUpdatedEpochMs = 1L),
        )
        assertEquals("s1e3", start?.id)
    }

    @Test
    fun startVideoRollsIntoNextSeasonAfterCompletedFinale() {
        val videos = listOf(video("s1e2", 1, 2), video("s2e1", 2, 1))
        val start = EpisodeDownloadPlanner.resolveStartVideo(
            videos,
            EpisodeAnchor(1, 2, isCompleted = true, lastUpdatedEpochMs = 1L),
        )
        assertEquals("s2e1", start?.id)
    }

    @Test
    fun startVideoFallsBackToFirstEpisode() {
        val videos = listOf(video("s1e1", 1, 1), video("s1e2", 1, 2))
        assertEquals("s1e1", EpisodeDownloadPlanner.resolveStartVideo(videos, null)?.id)
        // Anchor episode missing from the list -> start over.
        assertEquals(
            "s1e1",
            EpisodeDownloadPlanner.resolveStartVideo(
                videos,
                EpisodeAnchor(9, 9, isCompleted = false, lastUpdatedEpochMs = 1L),
            )?.id,
        )
    }

    @Test
    fun restOfSeasonStopsAtSeasonEnd() {
        val videos = listOf(
            video("s1e1", 1, 1), video("s1e2", 1, 2), video("s1e3", 1, 3),
            video("s2e1", 2, 1),
        )
        val start = videos[1]
        val rest = EpisodeDownloadPlanner.restOfSeasonVideos(videos, start)
        assertEquals(listOf("s1e2", "s1e3"), rest.map { it.id })
    }

    @Test
    fun nextNVideosCanCrossSeasons() {
        val videos = listOf(
            video("s1e2", 1, 2), video("s1e3", 1, 3),
            video("s2e1", 2, 1), video("s2e2", 2, 2),
        )
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[1], 3)
        assertEquals(listOf("s1e3", "s2e1", "s2e2"), next.map { it.id })
    }

    @Test
    fun distinctQualitiesAreBestFirst() {
        val streams = listOf(
            stream("Show 480p"),
            stream("Show 1080p"),
            stream("Show 720p"),
            stream("Show 1080p REMUX"),
            stream("No quality info"),
        )
        assertEquals(listOf("1080p", "720p", "480p"), EpisodeDownloadPlanner.distinctQualities(streams))
    }

    @Test
    fun closestQualityPrefersExactThenNearest() {
        val available = listOf("1080p", "480p")
        assertEquals("1080p", EpisodeDownloadPlanner.closestQuality(available, "1080p"))
        // 720p (rank 3) is nearer to 1080p (rank 4) than to 480p (rank 1).
        assertEquals("1080p", EpisodeDownloadPlanner.closestQuality(available, "720p"))
        assertEquals("480p", EpisodeDownloadPlanner.closestQuality(available, "480p"))
        assertNull(EpisodeDownloadPlanner.closestQuality(emptyList(), "720p"))
    }

    @Test
    fun bestStreamAtQualityPrefersTorrents() {
        val http = stream("Show 720p", torrent = false)
        val torrent = stream("Show 720p WEB", torrent = true)
        // Addon order breaks ties: http first, but torrent preference wins.
        assertEquals(torrent, EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(http, torrent), "720p"))
        assertNull(EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(http, torrent), "2160p"))
    }

    @Test
    fun defaultQualityConstantIs720p() {
        assertEquals("720p", EpisodeDownloadPlanner.DEFAULT_QUALITY)
    }

    @Test
    fun unavailableEpisodesAreSkipped() {
        val videos = listOf(video("s1e1", 1, 1), video("s1e2", 1, 2, available = false), video("s1e3", 1, 3))
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[0], 5)
        assertTrue(next.map { it.id } == listOf("s1e1", "s1e3"))
    }

    @Test
    fun nextNStartsAfterDownloadedEpisodes() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode in 1..3 }
        // On E1 with E1–E3 saved, "next 3" yields E4, E5, E6.
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[0], 3, downloaded)
        assertEquals(listOf("s1e4", "s1e5", "s1e6"), next.map { it.id })
    }

    @Test
    fun nextNAnchorsOnCurrentEpisodeWhenAheadOfDownloads() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode == 2 }
        // On E5 with only E2 saved, the window starts after the current episode.
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[4], 3, downloaded)
        assertEquals(listOf("s1e6", "s1e7", "s1e8"), next.map { it.id })
    }

    @Test
    fun nextNFiltersDownloadedInsideWindow() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        // Gap before the anchor: E2 saved but E1 (current) not; the window
        // still starts after the highest downloaded episode (E2).
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode == 2 }
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[0], 3, downloaded)
        assertEquals(listOf("s1e3", "s1e4", "s1e5"), next.map { it.id })
    }

    @Test
    fun nextNSkipsDownloadedInsideWindowAcrossSeasons() {
        val videos = listOf(
            video("s1e8", 1, 8),
            video("s2e1", 2, 1), video("s2e2", 2, 2), video("s2e3", 2, 3), video("s2e4", 2, 4),
        )
        // S1E8 saved; S2E2 saved inside the cross-season window: it is
        // filtered out and the take extends to S2E4.
        val downloaded: (Int, Int) -> Boolean = { season, episode ->
            (season == 1 && episode == 8) || (season == 2 && episode == 2)
        }
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[0], 3, downloaded)
        assertEquals(listOf("s2e1", "s2e3", "s2e4"), next.map { it.id })
    }

    @Test
    fun nextNCrossesSeasonAfterDownloadedFinale() {
        val videos = listOf(
            video("s1e7", 1, 7), video("s1e8", 1, 8),
            video("s2e1", 2, 1), video("s2e2", 2, 2), video("s2e3", 2, 3),
        )
        val downloaded: (Int, Int) -> Boolean = { season, episode -> season == 1 && episode >= 7 }
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[1], 3, downloaded)
        assertEquals(listOf("s2e1", "s2e2", "s2e3"), next.map { it.id })
    }

    @Test
    fun restOfSeasonExcludesDownloadedEpisodes() {
        val videos = listOf(
            video("s1e1", 1, 1), video("s1e2", 1, 2), video("s1e3", 1, 3),
            video("s2e1", 2, 1),
        )
        val downloaded: (Int, Int) -> Boolean = { season, episode -> season == 1 && episode <= 2 }
        val rest = EpisodeDownloadPlanner.restOfSeasonVideos(videos, videos[0], downloaded)
        assertEquals(listOf("s1e3"), rest.map { it.id })
    }

    @Test
    fun restOfSeasonIsEmptyWhenAllDownloaded() {
        val videos = listOf(video("s1e1", 1, 1), video("s1e2", 1, 2))
        val downloaded: (Int, Int) -> Boolean = { _, _ -> true }
        assertTrue(EpisodeDownloadPlanner.restOfSeasonVideos(videos, videos[0], downloaded).isEmpty())
        // Without the predicate the legacy behavior is unchanged.
        assertEquals(
            listOf("s1e1", "s1e2"),
            EpisodeDownloadPlanner.restOfSeasonVideos(videos, videos[0]).map { it.id },
        )
    }
}
