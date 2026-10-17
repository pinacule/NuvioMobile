package com.nuvio.app.features.watched

internal actual object WatchedClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
