package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class TorrentDownloadTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun realExtensionReplacesTheGuessedOne() {
        val item = downloadItem(fileName = "Movie_abc_1.mkv")
        assertEquals("Movie_abc_1.mp4", item.torrentOutputFileName("mp4"))
        assertEquals("Movie_abc_1.mkv", item.torrentOutputFileName("MKV"))
    }

    @Test
    fun unknownExtensionKeepsTheGuessedFileName() {
        val item = downloadItem(fileName = "Movie_abc_1.mkv")
        assertEquals("Movie_abc_1.mkv", item.torrentOutputFileName(null))
        assertEquals("Movie_abc_1.mkv", item.torrentOutputFileName("not an extension"))
    }

    @Test
    fun extensionIsReadFromTorrentFilePaths() {
        assertEquals("mkv", "Show.S01/Show.S01E02.1080p.WEB.mkv".mediaFileExtensionOrNull())
        assertEquals("mp4", "Movie (2020)\\Movie.2020.MP4".mediaFileExtensionOrNull())
        assertNull("Season.1/README".mediaFileExtensionOrNull())
        assertNull("folder.with.dots/file".mediaFileExtensionOrNull())
    }

    @Test
    fun storedHttpDownloadsDecodeWithoutTorrentFields() {
        val stored = """
            {"id":"a","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie","videoId":"tt1",
             "title":"Movie","streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mp4",
             "fileName":"Movie_a.mp4","status":"Completed","createdAtEpochMs":1,"updatedAtEpochMs":2}
        """.trimIndent()
        val item = json.decodeFromString(DownloadItem.serializer(), stored)
        assertEquals(DownloadSourceKind.Http, item.sourceKind)
        assertFalse(item.isTorrent)
        assertNull(item.torrentInfoHash)
    }

    @Test
    fun torrentFieldsSurviveARoundTrip() {
        val item = downloadItem(fileName = "Movie_a.mkv").copy(
            sourceKind = DownloadSourceKind.Torrent,
            torrentInfoHash = "a".repeat(40),
            torrentFileIdx = 3,
            torrentTrackers = listOf("udp://tracker.example:80/announce"),
            torrentFilenameHint = "Movie.2020.mkv",
        )
        val decoded = json.decodeFromString(DownloadItem.serializer(), json.encodeToString(DownloadItem.serializer(), item))
        assertEquals(item, decoded)
        assertTrue(decoded.isTorrent)
    }

    private fun downloadItem(fileName: String) = DownloadItem(
        id = "a",
        contentType = "movie",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        videoId = "tt1",
        title = "Movie",
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = "magnet:?xt=urn:btih:${"a".repeat(40)}",
        fileName = fileName,
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
