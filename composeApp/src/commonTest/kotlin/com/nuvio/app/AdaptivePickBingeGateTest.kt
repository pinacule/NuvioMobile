package com.nuvio.app

import com.nuvio.app.features.streams.StreamAutoPlayMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptivePickBingeGateTest {

    // (i) The orientation-test scenario: a binge group was persisted for the
    // series, the preference is on, reuse is off, mode is MANUAL — the pick
    // must stand down so the episode's sources stay in portrait.
    @Test
    fun `saved binge group with preference on and reuse off stands the pick down`() {
        assertTrue(
            adaptivePickStandsDownForBingeGroup(
                mode = StreamAutoPlayMode.MANUAL,
                preferBingeGroup = true,
                reuseBingeGroup = false,
                hasPersistedBingeGroup = true,
            ),
        )
    }

    // (ii) Reuse on: this gate stays out of the way; the pick already yields
    // to StreamAutoPlayPolicy.isEffectivelyEnabled and the repository's binge
    // flow owns playback.
    @Test
    fun `saved binge group with reuse on does not stand down at this gate`() {
        assertFalse(
            adaptivePickStandsDownForBingeGroup(
                mode = StreamAutoPlayMode.MANUAL,
                preferBingeGroup = true,
                reuseBingeGroup = true,
                hasPersistedBingeGroup = true,
            ),
        )
    }

    // (iii) Ordinary Play / Continue Watching: no binge group was ever saved
    // for the series, so item 9 fires as it did before the blanket guard.
    @Test
    fun `no saved binge group keeps the pick armed in manual mode`() {
        assertFalse(
            adaptivePickStandsDownForBingeGroup(
                mode = StreamAutoPlayMode.MANUAL,
                preferBingeGroup = true,
                reuseBingeGroup = false,
                hasPersistedBingeGroup = false,
            ),
        )
    }

    // (iv) Binge preference off: the pick stays armed regardless of what is
    // persisted (R-14 — the batch-21 null-tier reach is accepted here).
    @Test
    fun `preference off keeps the pick armed even with a saved group`() {
        assertFalse(
            adaptivePickStandsDownForBingeGroup(
                mode = StreamAutoPlayMode.MANUAL,
                preferBingeGroup = false,
                reuseBingeGroup = false,
                hasPersistedBingeGroup = true,
            ),
        )
        assertFalse(
            adaptivePickStandsDownForBingeGroup(
                mode = StreamAutoPlayMode.MANUAL,
                preferBingeGroup = false,
                reuseBingeGroup = true,
                hasPersistedBingeGroup = true,
            ),
        )
    }

    // (v) Configured auto-play modes are not this gate's business: settings
    // based auto-play owns them upstream.
    @Test
    fun `non-manual modes never stand down at this gate`() {
        for (mode in listOf(StreamAutoPlayMode.FIRST_STREAM, StreamAutoPlayMode.REGEX_MATCH)) {
            assertFalse(
                adaptivePickStandsDownForBingeGroup(
                    mode = mode,
                    preferBingeGroup = true,
                    reuseBingeGroup = false,
                    hasPersistedBingeGroup = true,
                ),
            )
        }
    }

    // (vi) Stand-down + the repository binge flow still undecided: the pick
    // must wait WITHOUT latching itself done — streams still loading, not
    // yet decided, or the direct auto-play overlay still up all keep the
    // binge flow's first claim alive.
    @Test
    fun `stand down with binge flow undecided keeps the pick waiting`() {
        // Not decided yet (and nothing else in flight).
        assertFalse(
            adaptivePickProceedsAfterBingeStandDown(
                standsDownForBingeGroup = true,
                autoPlayDecided = false,
                isAnyLoading = false,
                showDirectAutoPlayOverlay = false,
                hasAutoPlayStream = false,
            ),
        )
        // Still loading the sources the binge flow will choose from.
        assertFalse(
            adaptivePickProceedsAfterBingeStandDown(
                standsDownForBingeGroup = true,
                autoPlayDecided = false,
                isAnyLoading = true,
                showDirectAutoPlayOverlay = false,
                hasAutoPlayStream = false,
            ),
        )
        // Decided flag published early (non-direct flows do this) but groups
        // are still loading.
        assertFalse(
            adaptivePickProceedsAfterBingeStandDown(
                standsDownForBingeGroup = true,
                autoPlayDecided = true,
                isAnyLoading = true,
                showDirectAutoPlayOverlay = false,
                hasAutoPlayStream = false,
            ),
        )
        // Decided and loaded, but the direct auto-play overlay is still up.
        assertFalse(
            adaptivePickProceedsAfterBingeStandDown(
                standsDownForBingeGroup = true,
                autoPlayDecided = true,
                isAnyLoading = false,
                showDirectAutoPlayOverlay = true,
                hasAutoPlayStream = false,
            ),
        )
    }

    // (vii) Stand-down + the binge flow settled with NO stream: the
    // persisted group matched nothing in the freshly fetched list, so the
    // stand-down expires and the adaptive pick proceeds as the fallback.
    @Test
    fun `stand down with binge flow settled empty lets the pick proceed`() {
        assertTrue(
            adaptivePickProceedsAfterBingeStandDown(
                standsDownForBingeGroup = true,
                autoPlayDecided = true,
                isAnyLoading = false,
                showDirectAutoPlayOverlay = false,
                hasAutoPlayStream = false,
            ),
        )
    }

    // (viii) Stand-down + the binge flow settled WITH a stream: that stream
    // owns playback; the adaptive pick stays out.
    @Test
    fun `stand down with binge flow settled on a stream keeps the pick out`() {
        assertFalse(
            adaptivePickProceedsAfterBingeStandDown(
                standsDownForBingeGroup = true,
                autoPlayDecided = true,
                isAnyLoading = false,
                showDirectAutoPlayOverlay = false,
                hasAutoPlayStream = true,
            ),
        )
    }

    // (ix) No stand-down: this predicate imposes nothing at any flow state —
    // the pick follows its normal path exactly as before (its own downstream
    // guards still apply, e.g. an already-chosen auto-play stream).
    @Test
    fun `no stand down leaves the pick on its normal path`() {
        for (decided in listOf(false, true)) {
            for (loading in listOf(false, true)) {
                for (overlay in listOf(false, true)) {
                    for (hasStream in listOf(false, true)) {
                        assertTrue(
                            adaptivePickProceedsAfterBingeStandDown(
                                standsDownForBingeGroup = false,
                                autoPlayDecided = decided,
                                isAnyLoading = loading,
                                showDirectAutoPlayOverlay = overlay,
                                hasAutoPlayStream = hasStream,
                            ),
                        )
                    }
                }
            }
        }
    }

    // Exhaustive sweep: across every mode x preference x reuse x persisted
    // combination, exactly one stands the pick down — case (i).
    @Test
    fun `only the saved-group manual no-reuse combination stands down`() {
        var standDownCount = 0
        for (mode in StreamAutoPlayMode.entries) {
            for (prefer in listOf(false, true)) {
                for (reuse in listOf(false, true)) {
                    for (saved in listOf(false, true)) {
                        val standsDown = adaptivePickStandsDownForBingeGroup(
                            mode = mode,
                            preferBingeGroup = prefer,
                            reuseBingeGroup = reuse,
                            hasPersistedBingeGroup = saved,
                        )
                        if (standsDown) {
                            standDownCount++
                            assertTrue(mode == StreamAutoPlayMode.MANUAL && prefer && !reuse && saved)
                        }
                    }
                }
            }
        }
        assertTrue(standDownCount == 1)
    }
}
