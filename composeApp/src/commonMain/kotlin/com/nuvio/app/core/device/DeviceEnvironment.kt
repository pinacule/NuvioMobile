package com.nuvio.app.core.device

/**
 * Platform view of the device's network transport and battery state.
 *
 * Used by adaptive playback (quality selection), data saver mode and background
 * preloading. All calls are best-effort snapshots: [batteryPercent] returns -1 when
 * the level is unknown, and [isWifi]/[isCellular] return false when the transport
 * cannot be determined (treat as "unknown", never as a specific transport).
 */
expect object DeviceEnvironment {
    /** True when the active network is WiFi (unmetered). */
    fun isWifi(): Boolean

    /** True when the active network is cellular / mobile data. */
    fun isCellular(): Boolean

    /**
     * True when the active network is unmetered (not subject to per-byte
     * billing). A WiFi transport can still be metered — e.g. tethered to
     * another phone's hotspot — so this is separate from [isWifi]: [isWifi]
     * keeps its transport-class meaning for its other consumers, and only
     * gates that explicitly care about billing (background preloading) should
     * consult this. Returns false when meteredness cannot be determined.
     */
    fun isUnmetered(): Boolean

    /** Battery level 0..100, or -1 if unknown. */
    fun batteryPercent(): Int
}
