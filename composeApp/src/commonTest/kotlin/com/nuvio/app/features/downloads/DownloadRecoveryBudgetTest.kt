package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRecoveryBudgetTest {
    @Test
    fun failuresWithoutProgressBurnOutAtTheCap() {
        var budget = DownloadRecoveryBudget()
        repeat(4) { budget = assertNotNull(budget.consume(transferredBytes = 0L, maxConsecutive = 4)) }
        assertEquals(4, budget.consecutiveFailures)
        assertNull(budget.consume(transferredBytes = 0L, maxConsecutive = 4))
    }

    @Test
    fun progressSinceTheLastFailureStartsTheCountOver() {
        var budget = DownloadRecoveryBudget()
        repeat(3) { budget = assertNotNull(budget.consume(transferredBytes = 100L, maxConsecutive = 4)) }
        assertEquals(3, budget.consecutiveFailures)
        budget = assertNotNull(budget.consume(transferredBytes = 101L, maxConsecutive = 4))
        assertEquals(1, budget.consecutiveFailures)
        assertEquals(101L, budget.progressMark)
    }

    @Test
    fun anExhaustedBudgetIsReArmedByProgress() {
        var budget = DownloadRecoveryBudget()
        repeat(2) { budget = assertNotNull(budget.consume(transferredBytes = 500L, maxConsecutive = 2)) }
        assertNull(budget.consume(transferredBytes = 500L, maxConsecutive = 2))
        assertNotNull(budget.consume(transferredBytes = 501L, maxConsecutive = 2))
    }

    @Test
    fun aLongDownloadWithSpacedFailuresNeverBurnsOut() {
        // The field failure: ~1 GB episodes that each hit many transient
        // failures over their lifetime, with real progress in between.
        var budget = DownloadRecoveryBudget()
        for (failure in 1..50) {
            budget = assertNotNull(budget.consume(transferredBytes = failure * 20_000_000L, maxConsecutive = 2))
        }
        assertEquals(1, budget.consecutiveFailures)
    }

    @Test
    fun progressOnARestartedPartialCountsFromTheNewMark() {
        var budget = DownloadRecoveryBudget()
        budget = assertNotNull(budget.consume(transferredBytes = 400L, maxConsecutive = 2))
        // The partial was dropped (different encode) and the restart died early.
        budget = assertNotNull(budget.consume(transferredBytes = 50L, maxConsecutive = 2))
        assertEquals(2, budget.consecutiveFailures)
        assertEquals(50L, budget.progressMark)
        // Progress on the new file, still below the old mark, re-arms the budget.
        budget = assertNotNull(budget.consume(transferredBytes = 300L, maxConsecutive = 2))
        assertEquals(1, budget.consecutiveFailures)
    }

    @Test
    fun streamRefreshBudgetSemanticsAreUnchanged() {
        // StreamRefreshBudget (torrent loopback) now delegates here; its
        // contract: progress past the mark re-arms, deaths in place burn out.
        var budget = DownloadRecoveryBudget()
        budget = assertNotNull(budget.consume(100L, 2))
        budget = assertNotNull(budget.consume(100L, 2))
        assertNull(budget.consume(100L, 2))
        budget = assertNotNull(budget.consume(250L, 2))
        assertEquals(1, budget.consecutiveFailures)
    }

    @Test
    fun agedHttpLinksAreReResolvedBeforeTheirTransferStarts() {
        val item = downloadSourceItem(createdAt = 1_000L)
        assertFalse(item.hasAgedHttpSource(nowEpochMs = 1_000L + DOWNLOAD_SOURCE_MAX_AGE_MS - 1))
        assertTrue(item.hasAgedHttpSource(nowEpochMs = 1_000L + DOWNLOAD_SOURCE_MAX_AGE_MS))
        // A re-resolution restarts the clock.
        val refreshed = item.copy(sourceResolvedAtEpochMs = 10_000_000L)
        assertFalse(refreshed.hasAgedHttpSource(nowEpochMs = 10_000_000L + DOWNLOAD_SOURCE_MAX_AGE_MS - 1))
        // Torrent links are prepared by the engine at transfer time; never aged.
        val torrent = item.copy(sourceKind = DownloadSourceKind.Torrent)
        assertFalse(torrent.hasAgedHttpSource(nowEpochMs = Long.MAX_VALUE))
    }

    private fun downloadSourceItem(createdAt: Long) = DownloadItem(
        id = "d",
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:6",
        title = "Show",
        streamTitle = "Release",
        providerName = "Addon",
        sourceUrl = "https://example.com/e6.mkv",
        fileName = "e6.mkv",
        status = DownloadStatus.Downloading,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
    )
}
