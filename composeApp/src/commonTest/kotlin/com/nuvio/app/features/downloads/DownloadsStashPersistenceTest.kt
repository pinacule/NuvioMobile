package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadsStashPersistenceTest {
    @Test
    fun stashSurvivesCodecRoundTrip() {
        val stashed = downloadItem(id = "old", status = DownloadStatus.Completed, fileName = "show_old.mkv")
        val replacement = downloadItem(id = "new", status = DownloadStatus.Downloading, fileName = "show_new.mkv")
        val stash = StashedReplacement(item = stashed, fileUri = "file:///downloads/show_old.mkv")

        val decoded = DownloadsCodec.decode(
            DownloadsCodec.encodeItems(
                items = listOf(replacement),
                stashedReplacements = mapOf(replacement.id to stash),
            ),
        )

        assertEquals(listOf(replacement), decoded.items)
        assertEquals(mapOf(replacement.id to stash), decoded.stashedReplacements)
    }

    @Test
    fun payloadWithoutStashFieldDecodesWithEmptyStash() {
        // Payloads written before stashes were persisted have no such field.
        val decoded = DownloadsCodec.decode("""{"items":[]}""")

        assertTrue(decoded.items.isEmpty())
        assertTrue(decoded.stashedReplacements.isEmpty())
    }

    @Test
    fun failedReplacementRestoresStashedItemOnLoad() {
        val stashed = downloadItem(id = "old", status = DownloadStatus.Completed, fileName = "show_old.mkv")
        val replacement = downloadItem(id = "new", status = DownloadStatus.Failed, fileName = "show_new.mkv")
        val stash = StashedReplacement(item = stashed, fileUri = "file:///downloads/show_old.mkv")

        val settlement = settlePersistedStashes(listOf(replacement), mapOf(replacement.id to stash))

        assertEquals(listOf(stashed), settlement.items)
        assertTrue(settlement.remainingStashes.isEmpty())
        assertTrue(settlement.completedStashes.isEmpty())
        assertEquals(listOf(replacement.fileName), settlement.restoredReplacementFileNames)
    }

    @Test
    fun missingReplacementRestoresStashedItemOnLoad() {
        // A cancelled replacement is no longer in the list at all.
        val stashed = downloadItem(id = "old", status = DownloadStatus.Completed, fileName = "show_old.mkv")
        val stash = StashedReplacement(item = stashed, fileUri = "file:///downloads/show_old.mkv")

        val settlement = settlePersistedStashes(emptyList(), mapOf("new" to stash))

        assertEquals(listOf(stashed), settlement.items)
        assertTrue(settlement.remainingStashes.isEmpty())
    }

    @Test
    fun completedReplacementRetiresStashOnLoad() {
        val stashed = downloadItem(id = "old", status = DownloadStatus.Completed, fileName = "show_old.mkv")
        val replacement = downloadItem(id = "new", status = DownloadStatus.Completed, fileName = "show_new.mkv")
        val stash = StashedReplacement(item = stashed, fileUri = "file:///downloads/show_old.mkv")

        val settlement = settlePersistedStashes(listOf(replacement), mapOf(replacement.id to stash))

        assertEquals(listOf(replacement), settlement.items)
        assertTrue(settlement.remainingStashes.isEmpty())
        assertEquals(listOf(stash), settlement.completedStashes)
        assertTrue(settlement.restoredReplacementFileNames.isEmpty())
    }

    @Test
    fun inFlightAndPausedReplacementsKeepStashOnLoad() {
        val stashed = downloadItem(id = "old", status = DownloadStatus.Completed, fileName = "show_old.mkv")
        val stash = StashedReplacement(item = stashed, fileUri = "file:///downloads/show_old.mkv")

        for (status in listOf(DownloadStatus.Downloading, DownloadStatus.Paused)) {
            val replacement = downloadItem(id = "new", status = status, fileName = "show_new.mkv")
            val settlement = settlePersistedStashes(listOf(replacement), mapOf(replacement.id to stash))

            assertEquals(listOf(replacement), settlement.items)
            assertEquals(mapOf(replacement.id to stash), settlement.remainingStashes)
            assertTrue(settlement.completedStashes.isEmpty())
        }
    }

    private fun downloadItem(id: String, status: DownloadStatus, fileName: String) = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:6",
        title = "Show",
        streamTitle = "Release",
        providerName = "Addon",
        sourceUrl = "https://example.com/e6.mkv",
        fileName = fileName,
        status = status,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )
}
