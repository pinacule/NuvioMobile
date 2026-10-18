package com.nuvio.app.features.tracking

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

internal actual object TrackingScrobbleOutboxClock {
    @OptIn(ExperimentalForeignApi::class)
    actual fun nowEpochMs(): Long = time(null) * 1000L
}
