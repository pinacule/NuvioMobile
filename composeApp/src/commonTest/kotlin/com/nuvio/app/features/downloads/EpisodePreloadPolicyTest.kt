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
        dataSaver: Boolean = false,
        unmetered: Boolean = true,
        triggered: Boolean = false,
        series: Boolean = true,
    ) = EpisodePreloadPolicy.shouldTriggerPreload(
        progressFraction = fraction,
        isWifi = wifi,
        isCellular = cellular,
        batteryPercent = battery,
        dataSaverActive = dataSaver,
        isUnmetered = unmetered,
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
    fun dataSaverBlocksEvenOnUnmeteredWifi() {
        assertFalse(gate(dataSaver = true))
        // The data-saver dimension is independent of the battery rule:
        // it blocks even where unknown battery alone would be allowed.
        assertFalse(gate(dataSaver = true, battery = -1))
    }

    @Test
    fun meteredWifiBlocks() {
        // WiFi transport but metered (e.g. another phone's hotspot).
        assertFalse(gate(unmetered = false))
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
