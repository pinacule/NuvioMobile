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
    fun closestQualityTiePrefersLower() {
        // Equidistant tiers: the lower one wins whatever the input order —
        // the tie-break AdaptiveStreamPicker and SeasonDownloads use.
        assertEquals("720p", EpisodeDownloadPlanner.closestQuality(listOf("1440p", "720p"), "1080p"))
        assertEquals("720p", EpisodeDownloadPlanner.closestQuality(listOf("720p", "1440p"), "1080p"))
    }

    @Test
    fun bestStreamAtQualityFallsBackToUnparseablePool() {
        // No candidate carries a resolution token: the episode still has
        // streams, so the planner ranks all of them (torrent first)
        // instead of reporting none — mirroring SeasonDownloads' own
        // unparseable last-resort pool.
        val torrent = stream("Show S01E01 WEB x265")
        val http = stream("Show S01E01 HDTV", torrent = false)
        assertEquals(torrent, EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(http, torrent), "720p"))
        assertEquals(
            torrent,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(http, torrent), "720p", preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun bestStreamAtQualityUnparseableFallbackNeedsNoParseableTier() {
        // A parseable tier exists (just not the requested one): the pick
        // stays null — the fallback is only for when no quality resolves.
        val parseable = stream("Show S01E01 720p")
        val unparseable = stream("Show S01E01 WEB")
        assertNull(EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(parseable, unparseable), "1080p"))
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
    fun dubAtRequestedQualityBeatsSubAtRequestedQuality() {
        val jp720 = stream("Show S01E01 720p WEB x265")
        val dub720 = stream("Show S01E01 720p Dual Audio WEB x265")
        val dub1080 = stream("Show S01E01 1080p Dual Audio WEB x265")
        // Dub over sub, always — but the requested tier is the primary key:
        // the 720p dub wins, NOT the 1080p dub, even though both are dubs.
        assertEquals(
            dub720,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(jp720, dub1080, dub720), "720p", preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun dubAtAnotherQualityBeatsSubAtRequestedQuality() {
        val jp720 = stream("Show S01E01 720p WEB x265")
        val dub1080 = stream("Show S01E01 1080p Dual Audio WEB x265")
        // No dub exists at the requested tier: the cross-tier dub still
        // beats the same-tier sub (dub over sub), as the fallback.
        assertEquals(
            dub1080,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(jp720, dub1080), "720p", preferEnglishAudio = true),
        )
    }

    @Test
    fun dubAtRequestedQualityBeatsBetterSeededDubAtAnotherQuality() {
        val dub720 = stream("Show S01E01 720p Dual Audio 10 seeders")
        val dub1080 = stream("Show S01E01 1080p Dual Audio 5000 seeders")
        // Seeders never pull the pick across tiers when a dub exists at the
        // requested tier: the user asked for 720p files.
        assertEquals(
            dub720,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(dub1080, dub720), "720p", preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun crossTierDubFallbackPicksTheClosestTier() {
        val dub480 = stream("Show S01E01 480p Dual Audio 900 seeders")
        val dub1080 = stream("Show S01E01 1080p Dual Audio 10 seeders")
        // No 720p dub: 1080p is one tier away, 480p is two — closest wins,
        // not the best-seeded one.
        assertEquals(
            dub1080,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(dub480, dub1080), "720p", preferEnglishAudio = true,
            ),
        )
        // Equidistant tiers (576p and 1080p around 720p): the lower wins.
        val dub576 = stream("Show S01E01 576p Dual Audio 10 seeders")
        assertEquals(
            dub576,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(dub1080, dub576), "720p", preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun dubPickAtRequestedQualityPrefersTorrentThenSeeders() {
        val dubHttp720 = stream("Show 720p Dual Audio 5000 seeders", torrent = false)
        val dubTorrent720Low = stream("Show 720p Dual Audio 10 seeders")
        val dubTorrent720High = stream("Show 720p Dual Audio 900 seeders")
        // Within the requested tier, the download ordering applies: torrent
        // streams first, then best-seeded.
        assertEquals(
            dubTorrent720High,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(dubHttp720, dubTorrent720Low, dubTorrent720High),
                "720p",
                preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun oversizeStreamAtRequestedQualityLosesToSaneOne() {
        val oversize = stream("Show S01E01 720p \uD83D\uDCBE 30 GB \uD83D\uDC64 9000")
        val sane = stream("Show S01E01 720p \uD83D\uDCBE 1.5 GB \uD83D\uDC64 10")
        // 30 GB is past the 720p sane ceiling (2.2 GB): seeders cannot save it
        // while a sane candidate exists at the same tier.
        assertEquals(
            sane,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(oversize, sane), "720p"),
        )
    }

    @Test
    fun allOversizePoolPicksTheLeastOversize() {
        val huge = stream("Show S01E01 720p \uD83D\uDCBE 30 GB \uD83D\uDC64 9000")
        val slightlyOver = stream("Show S01E01 720p \uD83D\uDCBE 3 GB \uD83D\uDC64 10")
        // Everything is oversize: the least-oversize member is picked
        // instead of nothing (and instead of the best-seeded one).
        assertEquals(
            slightlyOver,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(huge, slightlyOver), "720p"),
        )
    }

    @Test
    fun oversizeDubLosesToSaneDubAtTheSameTier() {
        val oversizeDub = stream("Show S01E01 720p Dual Audio \uD83D\uDCBE 30 GB \uD83D\uDC64 9000")
        val saneDub = stream("Show S01E01 720p Dual Audio \uD83D\uDCBE 1.5 GB \uD83D\uDC64 10")
        assertEquals(
            saneDub,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(oversizeDub, saneDub), "720p", preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun noDubAnywhereKeepsTierPick() {
        val jp720Low = stream("Show S01E01 720p 10 seeders")
        val jp720High = stream("Show S01E01 720p 900 seeders")
        val jp1080High = stream("Show S01E01 1080p 5000 seeders")
        // No English audio anywhere: the requested tier still decides.
        assertEquals(
            jp720High,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(
                listOf(jp1080High, jp720Low, jp720High),
                "720p",
                preferEnglishAudio = true,
            ),
        )
    }

    @Test
    fun dubPreferenceOffKeepsTierPick() {
        val jp720 = stream("Show S01E01 720p WEB x265")
        val dub1080 = stream("Show S01E01 1080p Dual Audio WEB x265")
        assertEquals(
            jp720,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(dub1080, jp720), "720p", preferEnglishAudio = false),
        )
        assertEquals(
            jp720,
            EpisodeDownloadPlanner.pickBestStreamAtQuality(listOf(dub1080, jp720), "720p"),
        )
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
    fun nextNIncludesCurrentEpisodeWhenEarlierEpisodesDownloaded() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode in 1..3 }
        // On E5 with E1–E3 saved, "next 3" yields E5, E6, E7: the current
        // episode is not finished, so it heads the window (batch-20 change;
        // the old anchoring skipped it and returned E6–E8).
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[4], 3, downloaded)
        assertEquals(listOf("s1e5", "s1e6", "s1e7"), next.map { it.id })
    }

    @Test
    fun nextNStartsAfterCurrentWhenCurrentAlsoDownloaded() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode in 1..5 }
        // On E5 with E5 itself saved too, the window starts at the next
        // not-fully-downloaded episode: E6.
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[4], 3, downloaded)
        assertEquals(listOf("s1e6", "s1e7", "s1e8"), next.map { it.id })
    }

    @Test
    fun nextNIncludesCurrentEpisodeWhileItsDownloadIsInProgress() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        // The planner predicate reports only COMPLETED downloads (the caller
        // wires DownloadsRepository.hasCompletedDownload), so an episode that
        // is still downloading or paused counts as not finished and stays in
        // the window; SeasonDownloads.enqueueVideos partitions already-active
        // episodes out as covered instead of duplicating them.
        val completed = setOf(1, 2, 3)
        val inProgress = setOf(5)
        val fullyDownloaded: (Int, Int) -> Boolean = { _, episode -> episode in completed }
        assertTrue(5 in inProgress && 5 !in completed)
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[4], 3, fullyDownloaded)
        assertEquals(listOf("s1e5", "s1e6", "s1e7"), next.map { it.id })
    }

    @Test
    fun nextNFillsGapsAfterCurrentInEpisodeOrder() {
        val videos = (1..10).map { video("s1e$it", 1, it) }
        // E6 and E8 are already saved: gaps after the current episode are
        // filled in episode order and the take extends past them.
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode == 6 || episode == 8 }
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[4], 3, downloaded)
        assertEquals(listOf("s1e5", "s1e7", "s1e9"), next.map { it.id })
    }

    @Test
    fun nextNFiltersDownloadedInsideWindow() {
        val videos = (1..8).map { video("s1e$it", 1, it) }
        // Gap at the anchor: E2 saved but E1 (current) not. The walk starts
        // at the current episode, so E1 heads the window and E2 is skipped.
        val downloaded: (Int, Int) -> Boolean = { _, episode -> episode == 2 }
        val next = EpisodeDownloadPlanner.nextNVideos(videos, videos[0], 3, downloaded)
        assertEquals(listOf("s1e1", "s1e3", "s1e4"), next.map { it.id })
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
