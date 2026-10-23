package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class P2pStallTrackerTest {

    private fun tracker() = P2pStallTracker(stallWindowMs = 45_000L)

    @Test
    fun firstSampleOnlyEstablishesBaseline() {
        val tracker = tracker()
        // Even a huge first timestamp with zero progress must not fire:
        // there is no earlier baseline to measure silence against.
        assertFalse(
            tracker.onSample(nowMs = 10_000_000L, deliveredBytes = 0L, bufferProgress = 0f, starving = true),
        )
    }

    @Test
    fun sustainedSilenceWhileStarvingTripsAtTheWindow() {
        val tracker = tracker()
        assertFalse(tracker.onSample(0L, 1_000L, 0.10f, starving = true))
        assertFalse(tracker.onSample(44_999L, 1_000L, 0.10f, starving = true))
        assertTrue(tracker.onSample(45_000L, 1_000L, 0.10f, starving = true))
    }

    @Test
    fun deliveredBytesProgressRearmsTheWindow() {
        val tracker = tracker()
        assertFalse(tracker.onSample(0L, 1_000L, 0.10f, starving = true))
        // Bytes arrive just before the window would elapse.
        assertFalse(tracker.onSample(44_000L, 2_000L, 0.10f, starving = true))
        assertFalse(tracker.onSample(88_999L, 2_000L, 0.10f, starving = true))
        assertTrue(tracker.onSample(89_000L, 2_000L, 0.10f, starving = true))
    }

    @Test
    fun bufferProgressAloneCountsAsProgress() {
        val tracker = tracker()
        assertFalse(tracker.onSample(0L, 1_000L, 0.10f, starving = true))
        assertFalse(tracker.onSample(40_000L, 1_000L, 0.11f, starving = true))
        assertFalse(tracker.onSample(84_999L, 1_000L, 0.11f, starving = true))
        assertTrue(tracker.onSample(85_000L, 1_000L, 0.11f, starving = true))
    }

    @Test
    fun notStarvingHoldsTheClockSoPausesNeverTrip() {
        val tracker = tracker()
        assertFalse(tracker.onSample(0L, 1_000L, 0.10f, starving = true))
        // Long stretch where the player is not starving (paused/playing
        // from buffer): the clock is held, so no stall can accumulate.
        assertFalse(tracker.onSample(120_000L, 1_000L, 0.10f, starving = false))
        assertFalse(tracker.onSample(240_000L, 1_000L, 0.10f, starving = false))
        // Starving again: a fresh full window must pass before it trips.
        assertFalse(tracker.onSample(284_999L, 1_000L, 0.10f, starving = true))
        assertTrue(tracker.onSample(285_000L, 1_000L, 0.10f, starving = true))
    }

    @Test
    fun regressingCountersDoNotCountAsProgress() {
        val tracker = tracker()
        assertFalse(tracker.onSample(0L, 5_000L, 0.50f, starving = true))
        // Counters jitter backwards (a fresh engine session re-reporting):
        // only values beyond the best seen count as progress.
        assertFalse(tracker.onSample(20_000L, 4_000L, 0.40f, starving = true))
        assertTrue(tracker.onSample(45_000L, 4_500L, 0.45f, starving = true))
    }

    @Test
    fun resetRestartsTheBaseline() {
        val tracker = tracker()
        assertFalse(tracker.onSample(0L, 1_000L, 0.10f, starving = true))
        assertTrue(tracker.onSample(45_000L, 1_000L, 0.10f, starving = true))
        tracker.reset()
        assertFalse(tracker.onSample(46_000L, 0L, 0f, starving = true))
        assertFalse(tracker.onSample(90_999L, 0L, 0f, starving = true))
        assertTrue(tracker.onSample(91_000L, 0L, 0f, starving = true))
    }
}
