package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wi-Fi-only download rule ([DownloadWifiGate]) and the notification
 * content mapping that the Android notification builder branches on.
 * Pure functions: no Android framework, no repository, no network.
 */
class DownloadWifiGateTest {

    private fun item(
        status: DownloadStatus,
        waitingForWifi: Boolean = false,
    ) = DownloadItem(
        id = "dl",
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:6",
        title = "Show",
        streamTitle = "Release",
        providerName = "Addon",
        sourceUrl = "https://example.com/e6.mkv",
        fileName = "e6.mkv",
        status = status,
        downloadedBytes = 512L,
        totalBytes = 1024L,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        waitingForWifi = waitingForWifi,
    )

    @Test
    fun transferAllowedOnlyOnWifi() {
        assertTrue(DownloadWifiGate.transferAllowed(isWifi = true))
        assertFalse(DownloadWifiGate.transferAllowed(isWifi = false))
    }

    @Test
    fun parkAsWaitingIsPausedNeverFailedAndKeepsProgress() {
        val parked = DownloadWifiGate.parkAsWaiting(
            item(status = DownloadStatus.Downloading),
        )
        assertEquals(DownloadStatus.Paused, parked.status)
        assertTrue(parked.waitingForWifi)
        assertEquals(512L, parked.downloadedBytes)
        assertEquals(1024L, parked.totalBytes)
        assertEquals("dl", parked.id)
    }

    @Test
    fun parkAsWaitingIsUnconditionalSoCallersMustFilter() {
        // parkAsWaiting is a plain copy: it does not itself refuse terminal
        // items. The filtering lives in the callers (pauseDownloadsForWifi
        // only touches Downloading items; the start guard only runs for
        // items being started). This test pins that contract so a future
        // caller does not assume the copy is safe to apply blindly.
        val parkedFailed = DownloadWifiGate.parkAsWaiting(item(status = DownloadStatus.Failed))
        assertEquals(DownloadStatus.Paused, parkedFailed.status)
        assertTrue(parkedFailed.waitingForWifi)
    }

    @Test
    fun autoResumeOnlyForParkedItemsOnWifi() {
        assertTrue(
            DownloadWifiGate.shouldAutoResume(
                item(status = DownloadStatus.Paused, waitingForWifi = true),
                isWifi = true,
            ),
        )
        // User-paused (flag unset) never auto-resumes, even on Wi-Fi.
        assertFalse(
            DownloadWifiGate.shouldAutoResume(
                item(status = DownloadStatus.Paused, waitingForWifi = false),
                isWifi = true,
            ),
        )
        // Parked but still off Wi-Fi does not resume.
        assertFalse(
            DownloadWifiGate.shouldAutoResume(
                item(status = DownloadStatus.Paused, waitingForWifi = true),
                isWifi = false,
            ),
        )
        // A running download is not a resume candidate.
        assertFalse(
            DownloadWifiGate.shouldAutoResume(
                item(status = DownloadStatus.Downloading, waitingForWifi = false),
                isWifi = true,
            ),
        )
        // A finished download never resumes.
        assertFalse(
            DownloadWifiGate.shouldAutoResume(
                item(status = DownloadStatus.Completed, waitingForWifi = true),
                isWifi = true,
            ),
        )
    }

    @Test
    fun isWaitingRequiresPausedAndFlag() {
        assertTrue(DownloadWifiGate.isWaiting(item(status = DownloadStatus.Paused, waitingForWifi = true)))
        assertFalse(DownloadWifiGate.isWaiting(item(status = DownloadStatus.Paused, waitingForWifi = false)))
        assertFalse(DownloadWifiGate.isWaiting(item(status = DownloadStatus.Downloading, waitingForWifi = true)))
        assertFalse(DownloadWifiGate.isWaiting(item(status = DownloadStatus.Completed, waitingForWifi = true)))
    }

    @Test
    fun notificationContentMapping() {
        assertEquals(
            DownloadNotificationContent.Progress,
            downloadNotificationContentFor(item(status = DownloadStatus.Downloading)),
        )
        assertEquals(
            DownloadNotificationContent.WaitingForWifi,
            downloadNotificationContentFor(
                item(status = DownloadStatus.Paused, waitingForWifi = true),
            ),
        )
        assertEquals(
            DownloadNotificationContent.Paused,
            downloadNotificationContentFor(
                item(status = DownloadStatus.Paused, waitingForWifi = false),
            ),
        )
        assertEquals(
            DownloadNotificationContent.Failed,
            downloadNotificationContentFor(item(status = DownloadStatus.Failed)),
        )
        assertEquals(
            DownloadNotificationContent.Finished,
            downloadNotificationContentFor(item(status = DownloadStatus.Completed)),
        )
    }

    @Test
    fun waitingItemKeepsItsOwnContentEvenWithBytes() {
        // A half-downloaded episode parked mid-season shows "Waiting for
        // Wi-Fi", not progress: progress is frozen, not advancing.
        val parked = DownloadWifiGate.parkAsWaiting(item(status = DownloadStatus.Downloading))
        assertEquals(DownloadNotificationContent.WaitingForWifi, downloadNotificationContentFor(parked))
    }
}
