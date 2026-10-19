package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.streamResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeasonDownloadMatchingTest {
    private val packHash = "a".repeat(40)

    @Test
    fun seasonPackTorrentIsReusedForOtherEpisodes() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash, fileIdx = 0)
        val other = torrent("Torrentio\n1080p", "Show.S01E02.1080p.WEB.x264-OTHER", "b".repeat(40))
        val pack = torrent("Torrentio\n1080p", "Show.S01.1080p.WEB.x265-GRP", packHash, fileIdx = 1)

        assertEquals(pack, pickMatchingEpisodeStream(template, listOf(other, pack)))
    }

    @Test
    fun closestReleaseOfTheSameResolutionWins() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash)
        val wrongResolution = torrent("Torrentio\n2160p", "Show.S01E05.2160p.WEB.x265-GRP", "c".repeat(40))
        val otherGroup = torrent("Torrentio\n1080p", "Show.S01E05.1080p.BluRay.x264-OTHER", "d".repeat(40))
        val sameGroup = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x265-GRP", "e".repeat(40))

        assertEquals(sameGroup, pickMatchingEpisodeStream(template, listOf(wrongResolution, otherGroup, sameGroup)))
    }

    @Test
    fun noStreamOfTheSameResolutionMeansNoMatch() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB-GRP", packHash)
        val only4k = torrent("Torrentio\n4k", "Show.S01E05.2160p.WEB-GRP", "c".repeat(40))

        assertNull(pickMatchingEpisodeStream(template, listOf(only4k)))
    }

    @Test
    fun resolutionIsReadFromNamesAndTitles() {
        assertEquals("2160p", torrent("Torrentio\n4k", "x", packHash).streamResolution())
        assertEquals("720p", torrent("Addon", "Show 720p WEB", packHash).streamResolution())
        assertNull(torrent("Addon", "Show WEB", packHash).streamResolution())
    }

    private fun torrent(name: String, title: String, infoHash: String, fileIdx: Int? = null) = StreamItem(
        name = name,
        title = title,
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = "Torrentio",
        addonId = "addon:torrentio:https://example.com/manifest.json",
    )
}
