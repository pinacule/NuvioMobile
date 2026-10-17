package com.nuvio.app.features.player

import java.io.File

/**
 * Desktop stubs: ExoPlayer-specific native memory tuning does not exist on
 * the JVM. Heap sizing mirrors the Android heuristic; cache space is read
 * from the temp directory.
 */
internal actual fun applyExoPlayerNativeMemory(enabled: Boolean) = Unit

internal actual fun isExoNativeMemorySupported(): Boolean = false

internal actual fun exoNativeMemoryInfo(): ExoNativeMemoryInfo? = null

internal actual fun playbackCacheUsableSpaceBytes(): Long =
    runCatching {
        File(System.getProperty("java.io.tmpdir")).usableSpace.coerceAtLeast(0L)
    }.getOrDefault(0L)
