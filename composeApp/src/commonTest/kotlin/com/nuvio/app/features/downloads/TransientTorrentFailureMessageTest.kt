package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientTorrentFailureMessageTest {
    @Test
    fun preparationCollisionsAreTransient() {
        assertTrue(isTransientTorrentFailureMessage("stream preparation already pending"))
        assertTrue(isTransientTorrentFailureMessage("Timed out waiting for stream preparation"))
        assertTrue(isTransientTorrentFailureMessage("STREAM PREPARATION FAILED"))
    }

    @Test
    fun staleHttpCodesAreTransient() {
        // DownloadHttpException's message shape: a retry re-prepares a fresh
        // loopback stream, so an expired link must not be terminal.
        assertTrue(isTransientTorrentFailureMessage("Download failed: HTTP 403"))
        assertTrue(isTransientTorrentFailureMessage("Download failed: HTTP 404"))
        assertTrue(isTransientTorrentFailureMessage("Download failed: HTTP 410"))
    }

    @Test
    fun otherFailuresAreNotTransient() {
        assertFalse(isTransientTorrentFailureMessage("Download failed: HTTP 401"))
        assertFalse(isTransientTorrentFailureMessage("Download failed: HTTP 500"))
        // A longer code that merely starts with a stale code is not a stale link.
        assertFalse(isTransientTorrentFailureMessage("Download failed: HTTP 4040"))
        assertFalse(isTransientTorrentFailureMessage("Unable to resolve host"))
        assertFalse(isTransientTorrentFailureMessage(""))
    }
}
