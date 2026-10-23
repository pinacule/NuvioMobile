package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktScrobbleQueueableTest {

    @Test
    fun `queueable failure classification`() {
        // Only the dedicated queueable exception (thrown for Trakt 429/5xx
        // responses) marks a failure as fit for outbox replay.
        assertTrue(isTraktQueueableFailure(TraktScrobbleQueueableException(status = 429)))
        assertTrue(isTraktQueueableFailure(TraktScrobbleQueueableException(status = 500)))
        assertTrue(isTraktQueueableFailure(TraktScrobbleQueueableException(status = 503)))

        assertFalse(isTraktQueueableFailure(IllegalStateException("boom")))
        assertFalse(isTraktQueueableFailure(IllegalArgumentException("bad request")))
    }
}
