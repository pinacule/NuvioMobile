package com.nuvio.app.features.player

/**
 * JVM heap heuristic, mirroring the Android implementation:
 * budget a share of the max heap for the playback buffer, rounded down to
 * the target-size step.
 */
internal actual fun playbackHeapBufferMaxMb(): Int {
    val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
    val lowRam = maxHeapMb < 512L
    val raw = (maxHeapMb * if (lowRam) 0.65 else 0.85).toInt()
    val budget = if (lowRam) {
        raw.coerceAtMost((maxHeapMb - 210L).toInt()).coerceAtLeast(100)
    } else {
        raw
    }
    return (budget / PlaybackBufferSettings.TARGET_STEP_MB * PlaybackBufferSettings.TARGET_STEP_MB)
        .coerceIn(PlaybackBufferSettings.MIN_TARGET_MB, 4_096)
}
