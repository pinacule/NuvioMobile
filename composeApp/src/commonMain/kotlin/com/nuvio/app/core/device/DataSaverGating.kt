package com.nuvio.app.core.device

/**
 * Pure gating rules shared by background work (preloading, prefetch).
 *
 * Preloading is allowed only on WiFi with a healthy battery: never on other
 * transports, never with data saver active, and never at 25% battery or below.
 * Unknown battery (-1) is treated as not-healthy (fail closed: no preload).
 */
object DataSaverGating {
    fun shouldPreload(
        isWifi: Boolean,
        batteryPercent: Int,
        dataSaverActive: Boolean,
    ): Boolean {
        if (dataSaverActive) return false
        if (!isWifi) return false
        if (batteryPercent <= 25) return false
        return true
    }
}
