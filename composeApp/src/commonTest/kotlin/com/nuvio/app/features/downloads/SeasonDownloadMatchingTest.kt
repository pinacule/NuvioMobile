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

    @Test
    fun englishDubAtAnotherResolutionBeatsSameResolutionSub() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val sameResSub = torrent("Torrentio\n720p", "Show.S01E05.720p.WEB.x265-GRP", "f".repeat(40))
        val dub1080 = torrent("Torrentio\n1080p", "Show S01E05 1080p WEB Dual Audio x265-OTHER", "0".repeat(40))

        // Dub over sub, always: the 1080p dub wins over the same-resolution sub.
        assertEquals(
            dub1080,
            pickMatchingEpisodeStream(template, listOf(sameResSub, dub1080), preferEnglishAudio = true),
        )
        // Without the preference, the same-resolution release still wins.
        assertEquals(
            sameResSub,
            pickMatchingEpisodeStream(template, listOf(sameResSub, dub1080), preferEnglishAudio = false),
        )
        assertEquals(sameResSub, pickMatchingEpisodeStream(template, listOf(sameResSub, dub1080)))
    }

    @Test
    fun amongDubsTemplateResolutionIsTheDecidingKey() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val dub720 = torrent("Torrentio\n720p", "Show S01E05 720p WEB Dual Audio x265-OTHER", "1".repeat(40))
        val dub1080 = torrent("Torrentio\n1080p", "Show S01E05 1080p WEB Dual Audio x265-GRP", "2".repeat(40))

        // Both candidates are dubs; the one at the template's resolution wins
        // even though the 1080p release name is closer to the template (GRP tag).
        assertEquals(
            dub720,
            pickMatchingEpisodeStream(template, listOf(dub1080, dub720), preferEnglishAudio = true),
        )
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
