package com.nuvio.app.core.device

/** Desktop is a wired/unmetered device: treat the network as WiFi-like (never cellular). */
actual object DeviceEnvironment {
    actual fun isWifi(): Boolean = true

    actual fun isCellular(): Boolean = false

    actual fun batteryPercent(): Int = -1
}
