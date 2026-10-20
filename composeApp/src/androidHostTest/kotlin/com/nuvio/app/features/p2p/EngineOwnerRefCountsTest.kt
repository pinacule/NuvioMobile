package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for the engine-owner lease refcount (R-8): an owner key leased by
 * two overlapping holders must survive the first holder's release and only
 * disappear with the last one.
 */
class EngineOwnerRefCountsTest {

    @Test
    fun singleAcquireReleaseRemovesTheOwner() {
        val counts = EngineOwnerRefCounts()
        counts.acquire("playback")
        assertEquals(setOf("playback"), counts.snapshot())
        assertFalse(counts.isEmpty())

        assertTrue(counts.release("playback"))
        assertTrue(counts.isEmpty())
        assertEquals(0, counts.countOf("playback"))
    }

    @Test
    fun overlappingHoldsSurviveTheFirstRelease() {
        val counts = EngineOwnerRefCounts()
        counts.acquire("warmup:prefetch")
        counts.acquire("warmup:prefetch")
        assertEquals(2, counts.countOf("warmup:prefetch"))

        // First holder out: not the last hold, the lease stays up.
        assertFalse(counts.release("warmup:prefetch"))
        assertEquals(setOf("warmup:prefetch"), counts.snapshot())
        assertFalse(counts.isEmpty())

        // Last holder out: the owner is gone.
        assertTrue(counts.release("warmup:prefetch"))
        assertTrue(counts.isEmpty())
    }

    @Test
    fun releaseWithoutAcquireIsANoOp() {
        val counts = EngineOwnerRefCounts()
        assertFalse(counts.release("download:missing"))
        assertTrue(counts.isEmpty())

        counts.acquire("download:a")
        assertFalse(counts.release("download:missing"))
        assertEquals(1, counts.countOf("download:a"))
        assertEquals(setOf("download:a"), counts.snapshot())
    }

    @Test
    fun ownersAreCountedIndependently() {
        val counts = EngineOwnerRefCounts()
        counts.acquire("download:a")
        counts.acquire("download:a")
        counts.acquire("download:b")

        assertTrue(counts.release("download:b"))
        assertFalse(counts.release("download:a"))
        assertEquals(setOf("download:a"), counts.snapshot())

        assertTrue(counts.release("download:a"))
        assertTrue(counts.isEmpty())
    }

    @Test
    fun extraReleasesNeverDriveTheCountNegative() {
        val counts = EngineOwnerRefCounts()
        counts.acquire("playback")
        assertTrue(counts.release("playback"))
        assertFalse(counts.release("playback"))
        assertFalse(counts.release("playback"))
        assertEquals(0, counts.countOf("playback"))
        assertTrue(counts.isEmpty())
    }
}
