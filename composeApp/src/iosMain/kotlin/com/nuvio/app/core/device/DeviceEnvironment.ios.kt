package com.nuvio.app.core.device

/**
 * iOS stub: transport/battery bridging is not wired up yet. Conservative defaults —
 * never claim cellular (so data-saver never force-activates) and report an unknown
 * battery level (so low-battery rules never trigger spuriously).
 */
actual object DeviceEnvironment {
    actual fun isWifi(): Boolean = true

    actual fun isCellular(): Boolean = false

    /** Matches the stub's WiFi-like stance above: no metered link is reported. */
    actual fun isUnmetered(): Boolean = true

    actual fun batteryPercent(): Int = -1
}
