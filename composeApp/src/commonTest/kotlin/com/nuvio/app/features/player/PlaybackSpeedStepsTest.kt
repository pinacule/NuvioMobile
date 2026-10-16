package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedStepsTest {
    @Test
    fun sliderValuesSnapToFiveHundredthSteps() {
        assertEquals(1.25f, roundPlaybackSpeed(1.26f))
        assertEquals(1.75f, roundPlaybackSpeed(1.7499f))
        assertEquals(3.35f, roundPlaybackSpeed(3.333f))
    }

    @Test
    fun speedsStayBetweenOneAndFour() {
        assertEquals(1f, roundPlaybackSpeed(0.5f))
        assertEquals(4f, roundPlaybackSpeed(5f))
    }

    @Test
    fun presetsAreInsideTheSliderRange() {
        PLAYBACK_SPEED_PRESETS.forEach { preset ->
            assertEquals(preset, roundPlaybackSpeed(preset))
        }
    }

    @Test
    fun labelsDropTrailingZeros() {
        assertEquals("1x", formatPlaybackSpeedLabel(1f))
        assertEquals("1.75x", formatPlaybackSpeedLabel(1.75f))
        assertEquals("2.5x", formatPlaybackSpeedLabel(2.5f))
    }
}
