package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedStepsTest {
    @Test
    fun speedButtonCyclesThroughEveryStepAndWraps() {
        val visited = generateSequence(1f) { nextPlaybackSpeedStep(it) }.take(8).toList()
        assertEquals(listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 1f), visited)
    }

    @Test
    fun offStepSpeedMovesToTheNextHigherStep() {
        assertEquals(1.25f, nextPlaybackSpeedStep(1.1f))
        assertEquals(1f, nextPlaybackSpeedStep(3.5f))
    }

    @Test
    fun labelsDropTrailingZeros() {
        assertEquals("1x", formatPlaybackSpeedLabel(1f))
        assertEquals("1.75x", formatPlaybackSpeedLabel(1.75f))
        assertEquals("2.5x", formatPlaybackSpeedLabel(2.5f))
    }
}
