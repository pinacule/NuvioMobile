package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebridFileSelectorTest {
    @Test
    fun `Torbox selector does not treat torrent index as provider file id`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "small.mkv", size = 1),
            TorboxTorrentFileDto(id = 8, name = "target.mkv", size = 2),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(fileIdx = 8),
            season = null,
            episode = null,
        )

        assertNull(selected)
    }

    @Test
    fun `Torbox selector prefers filename match before provider file id`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 0, name = "Request High Bitrate Stuff in Here.txt", size = 1),
            TorboxTorrentFileDto(
                id = 85,
                name = "The Office US S01-S09/The.Office.US.S01E01.Pilot.1080p.BluRay.Remux.mkv",
                size = 5_303_936_915,
            ),
            TorboxTorrentFileDto(
                id = 1,
                name = "The Office US S01-S09/The.Office.US.S08E13.Jury.Duty.1080p.BluRay.Remux.mkv",
                size = 5_859_312_140,
            ),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(
                fileIdx = 1,
                season = 1,
                episode = 1,
                filename = "The.Office.US.S01E01.Pilot.1080p.BluRay.Remux.mkv",
            ),
            season = 1,
            episode = 1,
        )

        assertEquals(85, selected?.id)
    }

    @Test
    fun `Torbox selector treats fileIdx as source list index before provider file id`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 0, name = "Request High Bitrate Stuff in Here.txt", size = 1),
            TorboxTorrentFileDto(id = 85, name = "Show.S01E01.mkv", size = 500),
            TorboxTorrentFileDto(id = 1, name = "Show.S08E13.mkv", size = 900),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(fileIdx = 1),
            season = null,
            episode = null,
        )

        assertEquals(85, selected?.id)
    }

    @Test
    fun `Torbox selector uses episode pattern before broad title`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "The.Office.US.S08E13.Jury.Duty.mkv", size = 900),
            TorboxTorrentFileDto(id = 85, name = "The.Office.US.S01E01.Pilot.mkv", size = 500),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(
                season = 1,
                episode = 1,
                title = "The Office",
            ),
            season = 1,
            episode = 1,
        )

        assertEquals(85, selected?.id)
    }

    @Test
    fun `Torbox selector falls back to largest playable video`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "sample.txt", size = 999),
            TorboxTorrentFileDto(id = 2, name = "episode.mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "episode-1080p.mp4", size = 500),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(),
            season = null,
            episode = null,
        )

        assertEquals(3, selected?.id)
    }

    @Test
    fun `Torbox selector does not shift an out of range index`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 5, name = "first.mkv", size = 100),
            TorboxTorrentFileDto(id = 9, name = "second.mkv", size = 200),
        )

        assertNull(TorboxFileSelector().selectFile(files, resolve(fileIdx = 2), null, null))
        assertNull(TorboxFileSelector().selectFile(files, resolve(fileIdx = -1), null, null))
        assertEquals(5, TorboxFileSelector().selectFile(files, resolve(fileIdx = 0), null, null)?.id)
    }

    @Test
    fun `explicit filename takes priority over raw filename and episode`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 4, name = "Show.S01E01.mkv", size = 200),
            TorboxTorrentFileDto(id = 8, name = "Show.S01E02.mkv", size = 100),
        )
        val metadata = resolve(filename = "Show.S01E02.mkv").copy(
            stream = StreamClientResolveStream(raw = StreamClientResolveRaw(filename = "Show.S01E01.mkv")),
        )

        assertEquals(8, TorboxFileSelector().selectFile(files, metadata, 1, 1)?.id)
        assertEquals(4, TorboxFileSelector().selectFile(files, metadata.copy(filename = null), null, null)?.id)
    }

    @Test
    fun `filename matching preserves unicode punctuation and extension`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "作品一.mkv", size = 300),
            TorboxTorrentFileDto(id = 2, name = "作品二.mp4", size = 200),
            TorboxTorrentFileDto(id = 3, name = "作品二.mkv", size = 100),
            TorboxTorrentFileDto(id = 4, name = "Show.S01E02.mkv", size = 400),
            TorboxTorrentFileDto(id = 5, name = "Show-S01E02.mkv", size = 100),
        )

        assertEquals(3, TorboxFileSelector().selectFile(files, resolve(filename = "作品二.mkv"), null, null)?.id)
        assertEquals(5, TorboxFileSelector().selectFile(files, resolve(filename = "Show-S01E02.mkv"), null, null)?.id)
    }

    @Test
    fun `exact path distinguishes repeated basenames and handles windows separators`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Show/Season 1/Episode 02.mkv", size = 200),
            TorboxTorrentFileDto(id = 2, name = "Show/Season 2/Episode 02.mkv", size = 100),
        )

        assertEquals(2, TorboxFileSelector().selectFile(files, resolve(filename = "Season 2\\Episode 02.mkv"), null, null)?.id)
        assertNull(TorboxFileSelector().selectFile(files, resolve(filename = "Episode 02.mkv", fileIdx = 0), null, null))
    }

    @Test
    fun `exact filename case wins before case insensitive fallback`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Season 4/Attack on Titan - 02.mkv", size = 200),
            TorboxTorrentFileDto(id = 2, name = "Season 1/Attack On Titan - 02.mkv", size = 100),
        )

        assertEquals(2, TorboxFileSelector().selectFile(files, resolve(filename = "Attack On Titan - 02.mkv"), null, null)?.id)
        assertNull(TorboxFileSelector().selectFile(files, resolve(filename = "ATTACK ON TITAN - 02.MKV"), null, null))
        assertEquals(2, TorboxFileSelector().selectFile(files.takeLast(1), resolve(filename = "ATTACK ON TITAN - 02.MKV"), null, null)?.id)
    }

    @Test
    fun `episode fallback respects number boundaries and ignores parent folder`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Show.S01E02/Show.S01E20.mkv", size = 900),
            TorboxTorrentFileDto(id = 2, name = "Show.1x20.mkv", size = 800),
            TorboxTorrentFileDto(id = 3, name = "Show.1x02.mkv", size = 100),
        )

        assertEquals(3, TorboxFileSelector().selectFile(files, resolve(), 1, 2)?.id)
        assertNull(TorboxFileSelector().selectFile(files.take(2), resolve(fileIdx = 0), 1, 2))
    }

    @Test
    fun `missing episodes do not fall back to another file`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Show.S01E01.mkv", size = 900),
            TorboxTorrentFileDto(id = 2, name = "Show.S01E02.720p.mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "Show.S01E02.1080p.mkv", size = 400),
        )

        assertNull(TorboxFileSelector().selectFile(files, resolve(), 1, 3))
        assertNull(TorboxFileSelector().selectFile(files, resolve(filename = "missing.mkv", fileIdx = 0), null, null))
    }

    @Test
    fun `ambiguous episode matches pick the shortest exact single episode file`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Show.S01E01.mkv", size = 900),
            TorboxTorrentFileDto(id = 2, name = "Show.S01E02.720p.mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "Show.S01E02.1080p.mkv", size = 400),
        )

        assertEquals(2, TorboxFileSelector().selectFile(files, resolve(), 1, 2)?.id)
        assertEquals(2, TorboxFileSelector().selectFile(files.reversed(), resolve(), 1, 2)?.id)
    }

    @Test
    fun `episode pattern prefers exact single episode over multi episode file`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Show.S02E01E02.mkv", size = 900),
            TorboxTorrentFileDto(id = 2, name = "Show.S02E01.1080p.WEB-DL.x264.mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "Show.S02E02.mkv", size = 400),
        )

        assertEquals(2, TorboxFileSelector().selectFile(files, resolve(), 2, 1)?.id)
        assertEquals(2, TorboxFileSelector().selectFile(files.reversed(), resolve(), 2, 1)?.id)

        val rangeFiles = listOf(
            TorboxTorrentFileDto(id = 1, name = "Show.S02E01-02.mkv", size = 900),
            TorboxTorrentFileDto(id = 2, name = "Show.S02E01.mkv", size = 200),
        )

        assertEquals(2, TorboxFileSelector().selectFile(rangeFiles, resolve(), 2, 1)?.id)
    }

    @Test
    fun `absolute numbered pack file is picked via episode token fallback`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "[SubsPlease] Re:Zero - 01 (1080p).mkv", size = 100),
            TorboxTorrentFileDto(id = 2, name = "[SubsPlease] Re:Zero - 02 (1080p).mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "[SubsPlease] Re:Zero - 03 (1080p).mkv", size = 300),
        )

        val selected = TorboxFileSelector().selectFile(
            files = files,
            resolve = resolve(title = "Re:Zero"),
            season = 1,
            episode = 2,
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `episode only tokens are picked via fallback`() {
        val files = listOf(
            TorboxTorrentFileDto(id = 1, name = "Some Show - E01.mkv", size = 100),
            TorboxTorrentFileDto(id = 2, name = "Some Show - E02.mkv", size = 200),
            TorboxTorrentFileDto(id = 3, name = "Some Show EP03.mkv", size = 300),
        )

        assertEquals(
            2,
            TorboxFileSelector().selectFile(files, resolve(title = "Some Show"), 1, 2)?.id,
        )
        assertEquals(
            3,
            TorboxFileSelector().selectFile(files, resolve(title = "Some Show"), 1, 3)?.id,
        )
    }

    @Test
    fun `wrong absolute or wrong title files are not picked via fallback`() {
        val wrongNumbers = listOf(
            TorboxTorrentFileDto(id = 1, name = "Re:Zero - 03.mkv", size = 100),
            TorboxTorrentFileDto(id = 2, name = "Re:Zero - 04.mkv", size = 200),
        )

        assertNull(TorboxFileSelector().selectFile(wrongNumbers, resolve(title = "Re:Zero"), 1, 2))

        val wrongTitle = listOf(
            TorboxTorrentFileDto(id = 1, name = "Other Show - 02.mkv", size = 100),
            TorboxTorrentFileDto(id = 2, name = "Other Show - E02.mkv", size = 200),
        )

        assertNull(TorboxFileSelector().selectFile(wrongTitle, resolve(title = "Re:Zero"), 1, 2))

        val markedWithToken = listOf(
            TorboxTorrentFileDto(id = 1, name = "Re:Zero S01E03 - 02.mkv", size = 100),
        )

        assertNull(TorboxFileSelector().selectFile(markedWithToken, resolve(title = "Re:Zero"), 1, 2))
    }

    @Test
    fun `season two fallback matches episode token but cannot derive absolute number`() {
        // No absolute number or season offset reaches the selector, so a true
        // absolute-numbered file ("- 26" = S02E01) cannot be derived and must
        // not be guessed; a within-season token ("- 01") still matches.
        val absoluteOnly = listOf(
            TorboxTorrentFileDto(id = 1, name = "Re:Zero - 26.mkv", size = 100),
        )

        assertNull(TorboxFileSelector().selectFile(absoluteOnly, resolve(title = "Re:Zero"), 2, 1))

        val withRelative = absoluteOnly + TorboxTorrentFileDto(id = 2, name = "Re:Zero - 01.mkv", size = 200)

        assertEquals(2, TorboxFileSelector().selectFile(withRelative, resolve(title = "Re:Zero"), 2, 1)?.id)
    }

    @Test
    fun `Real-Debrid selector matches episode pattern before largest file`() {
        val files = listOf(
            RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 1_000),
            RealDebridTorrentFileDto(id = 2, path = "/Show.S01E02.mkv", bytes = 2_000),
        )

        val selected = RealDebridFileSelector().selectFile(
            files = files,
            resolve = resolve(season = 1, episode = 1),
            season = null,
            episode = null,
        )

        assertEquals(1, selected?.id)
    }

    @Test
    fun `Premiumize direct download selector ignores non-video and matches episode`() {
        val files = listOf(
            PremiumizeDirectDownloadFileDto(path = "Show/Readme.txt", size = 9_000, link = "https://pm/readme"),
            PremiumizeDirectDownloadFileDto(path = "Show/Show.S01E02.mkv", size = 2_000, link = "https://pm/e02"),
            PremiumizeDirectDownloadFileDto(path = "Show/Show.S01E01.mkv", size = 1_000, link = "https://pm/e01"),
        )

        val selected = PremiumizeDirectDownloadFileSelector().selectFile(
            files = files,
            resolve = resolve(season = 1, episode = 1),
            season = null,
            episode = null,
        )

        assertEquals("Show/Show.S01E01.mkv", selected?.path)
    }

    @Test
    fun `Premiumize direct download selector falls back to largest playable file`() {
        val files = listOf(
            PremiumizeDirectDownloadFileDto(path = "small.mp4", size = 1_000, link = "https://pm/small"),
            PremiumizeDirectDownloadFileDto(path = "large.mkv", size = 3_000, link = "https://pm/large"),
            PremiumizeDirectDownloadFileDto(path = "large-without-link.mkv", size = 9_000, link = null),
        )

        val selected = PremiumizeDirectDownloadFileSelector().selectFile(
            files = files,
            resolve = resolve(),
            season = null,
            episode = null,
        )

        assertEquals("large.mkv", selected?.path)
    }

    private fun resolve(
        fileIdx: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        filename: String? = null,
        title: String? = null,
    ): StreamClientResolve =
        StreamClientResolve(
            type = "debrid",
            service = DebridProviders.TORBOX_ID,
            isCached = true,
            infoHash = "hash",
            fileIdx = fileIdx,
            filename = filename,
            title = title,
            season = season,
            episode = episode,
        )
}
