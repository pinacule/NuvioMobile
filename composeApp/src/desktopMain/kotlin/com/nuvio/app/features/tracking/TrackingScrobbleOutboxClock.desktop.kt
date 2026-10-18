package com.nuvio.app.features.tracking

internal actual object TrackingScrobbleOutboxClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
