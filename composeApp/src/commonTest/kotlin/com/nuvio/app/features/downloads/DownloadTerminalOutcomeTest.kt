package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadTerminalOutcomeTest {
    @Test
    fun completingNotifiesFinished() {
        assertEquals(
            DownloadTerminalOutcome.Finished,
            downloadTerminalOutcome(DownloadStatus.Downloading, DownloadStatus.Completed),
        )
        // A download resumed from pause that then completes is still a finish.
        assertEquals(
            DownloadTerminalOutcome.Finished,
            downloadTerminalOutcome(DownloadStatus.Paused, DownloadStatus.Completed),
        )
    }

    @Test
    fun terminalFailureNotifiesFailed() {
        assertEquals(
            DownloadTerminalOutcome.Failed,
            downloadTerminalOutcome(DownloadStatus.Downloading, DownloadStatus.Failed),
        )
    }

    @Test
    fun retriedAttemptsDoNotNotify() {
        // Automatic retries (scheduler-level and the repository's torrent
        // preparation retries) keep the item Downloading across attempts:
        // Downloading -> Downloading is an attempt, not an outcome, so a
        // download that will be retried must not notify per attempt.
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Downloading, DownloadStatus.Downloading),
        )
    }

    @Test
    fun firstSightingNeverNotifies() {
        // Items restored from disk on app start have no previous status:
        // already-finished or already-failed downloads must stay silent.
        assertNull(downloadTerminalOutcome(null, DownloadStatus.Completed))
        assertNull(downloadTerminalOutcome(null, DownloadStatus.Failed))
        assertNull(downloadTerminalOutcome(null, DownloadStatus.Downloading))
    }

    @Test
    fun repeatedTerminalStatusDoesNotNotifyAgain() {
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Completed, DownloadStatus.Completed),
        )
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Failed, DownloadStatus.Failed),
        )
    }

    @Test
    fun nonTerminalTransitionsDoNotNotify() {
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Downloading, DownloadStatus.Paused),
        )
        // Manual retry of a failed download restarts it: not an outcome.
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Failed, DownloadStatus.Downloading),
        )
        // Re-downloading a completed item starts a fresh attempt.
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Completed, DownloadStatus.Downloading),
        )
        assertNull(
            downloadTerminalOutcome(DownloadStatus.Paused, DownloadStatus.Downloading),
        )
    }
}
