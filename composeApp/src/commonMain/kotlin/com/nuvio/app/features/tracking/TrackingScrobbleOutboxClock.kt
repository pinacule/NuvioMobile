package com.nuvio.app.features.tracking

internal expect object TrackingScrobbleOutboxClock {
    fun nowEpochMs(): Long
}
