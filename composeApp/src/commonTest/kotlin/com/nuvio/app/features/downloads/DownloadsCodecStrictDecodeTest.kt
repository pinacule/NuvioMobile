package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The strict decode variants back the repositories' last-good backup
 * recovery: a corrupt payload must be distinguishable from a legitimately
 * empty one, which the lenient decode (default on failure) cannot do.
 */
class DownloadsCodecStrictDecodeTest {
    @Test
    fun validPayloadDecodesStrictly() {
        val payload = DownloadsCodec.encodeItems(items = emptyList())
        assertNotNull(DownloadsCodec.decodeOrNull(payload))
    }

    @Test
    fun corruptPayloadFailsStrictDecode() {
        assertNull(DownloadsCodec.decodeOrNull("""{"items":[{"id":"""))
        assertNull(DownloadsCodec.decodeOrNull("not json at all"))
    }

    @Test
    fun lenientDecodeStillDefaultsOnCorruption() {
        val decoded = DownloadsCodec.decode("""{"items":[{"id":""")
        assertTrue(decoded.items.isEmpty())
        assertEquals(StoredDownloadsPayload(), decoded)
    }
}
