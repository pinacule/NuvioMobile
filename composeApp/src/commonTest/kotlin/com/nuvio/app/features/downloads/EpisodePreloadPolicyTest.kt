package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodePreloadPolicyTest {

    private fun gate(
        fraction: Float = 0.61f,
        wifi: Boolean = true,
        cellular: Boolean = false,
        battery: Int = 80,
        triggered: Boolean = false,
        series: Boolean = true,
    ) = EpisodePreloadPolicy.shouldTriggerPreload(
        progressFraction = fraction,
        isWifi = wifi,
        isCellular = cellular,
        batteryPercent = battery,
        alreadyTriggeredForVideo = triggered,
        isSeries = series,
    )

    @Test
    fun triggersOnWifiAboveThreshold() {
        assertTrue(gate())
    }

    @Test
    fun needsSixtyPercent() {
        assertFalse(gate(fraction = 0.59f))
        assertTrue(gate(fraction = 0.60f))
    }

    @Test
    fun neverOnCellular() {
        assertFalse(gate(wifi = false, cellular = true))
        // Even if both flags are somehow set, cellular wins.
        assertFalse(gate(wifi = true, cellular = true))
    }

    @Test
    fun needsWifi() {
        assertFalse(gate(wifi = false, cellular = false))
    }

    @Test
    fun batteryAtOrBelow25Blocks() {
        assertFalse(gate(battery = 25))
        assertFalse(gate(battery = 10))
        assertTrue(gate(battery = 26))
    }

    @Test
    fun unknownBatteryIsAllowed() {
        assertTrue(gate(battery = -1))
    }

    @Test
    fun onlyOncePerVideo() {
        assertFalse(gate(triggered = true))
    }

    @Test
    fun seriesOnly() {
        assertFalse(gate(series = false))
    }
}
