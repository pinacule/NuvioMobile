package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdbListTrackingWritesTest {

    @Test
    fun `queueable failure classification`() {
        fun apiError(status: Int?, code: String?) = MdbListApiException(
            status = status,
            code = code,
            retryAtEpochMs = 0L,
        )

        // Transport failures and rate limiting are queued for replay.
        assertTrue(isMdbListQueueableFailure(apiError(null, "transport_failure")))
        assertTrue(isMdbListQueueableFailure(apiError(null, "rate_limited")))
        assertTrue(isMdbListQueueableFailure(apiError(429, "http_error")))
        assertTrue(isMdbListQueueableFailure(apiError(408, "http_error")))
        assertTrue(isMdbListQueueableFailure(apiError(500, "http_error")))
        assertTrue(isMdbListQueueableFailure(apiError(503, "http_error")))
        assertTrue(isMdbListQueueableFailure(apiError(599, "http_error")))

        // Permanent failures must not be queued.
        assertFalse(isMdbListQueueableFailure(apiError(400, "http_error")))
        assertFalse(isMdbListQueueableFailure(apiError(401, "unauthorized")))
        assertFalse(isMdbListQueueableFailure(apiError(404, "http_error")))
        assertFalse(isMdbListQueueableFailure(apiError(200, "http_error")))
        assertFalse(isMdbListQueueableFailure(apiError(null, "unauthorized")))
        assertFalse(isMdbListQueueableFailure(IllegalStateException("boom")))
    }
}
