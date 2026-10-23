package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The strict decode variant backs the repository's last-good backup
 * recovery: a corrupt payload must be distinguishable from a legitimately
 * empty one, which the lenient decode (default on failure) cannot do.
 */
class WatchProgressCodecStrictDecodeTest {
    @Test
    fun validPayloadDecodesStrictly() {
        val payload = WatchProgressCodec.encodePayload(
            entries = emptyList(),
            lastSuccessfulPushEpochMs = 42L,
            deltaCursorEventId = 7L,
            deltaInitialized = true,
        )
        val decoded = assertNotNull(WatchProgressCodec.decodePayloadOrNull(payload))
        assertEquals(42L, decoded.lastSuccessfulPushEpochMs)
        assertEquals(7L, decoded.deltaCursorEventId)
        assertEquals(true, decoded.deltaInitialized)
    }

    @Test
    fun corruptPayloadFailsStrictDecode() {
        assertNull(WatchProgressCodec.decodePayloadOrNull("""{"entries":[{"""))
        assertNull(WatchProgressCodec.decodePayloadOrNull("not json at all"))
    }

    @Test
    fun lenientDecodeStillDefaultsOnCorruption() {
        assertEquals(
            StoredWatchProgressPayload(),
            WatchProgressCodec.decodePayload("""{"entries":[{"""),
        )
    }
}
