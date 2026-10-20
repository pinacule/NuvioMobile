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
