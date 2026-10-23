package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingRefreshIntent

internal const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
internal const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS =
    SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L
internal val simklConnectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC
internal val simklProgressRefreshIntent = TrackingRefreshIntent.AUTOMATIC

/**
 * How long automatic refreshes wait after the [consecutiveFailures]-th
 * consecutive sync failure: the plain automatic interval, doubling per
 * failure, capped at 32× the interval (8 h). At the cap a persistently
 * failing backend costs ~3 automatic rounds a day instead of an
 * unbounded stream of them — each round is several requests against
 * Simkl's 500/day quota.
 */
internal fun simklSyncFailureBackoffMs(consecutiveFailures: Int): Long {
    if (consecutiveFailures <= 0) return 0L
    val doublings = (consecutiveFailures - 1).coerceAtMost(5)
    return SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS shl doublings
}

internal fun shouldRunSimklRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    consecutiveSyncFailures: Int = 0,
    lastSyncFailureAtEpochMs: Long? = null,
    automaticIntervalMs: Long = SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS,
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError) {
        // An errored snapshot no longer bypasses the gate: automatic
        // triggers must wait out the failure backoff. A failure recorded
        // without a timestamp (legacy state) retries once immediately,
        // as before; manual refreshes never reach this branch.
        val failedAtEpochMs = lastSyncFailureAtEpochMs ?: return true
        val elapsedMs = nowEpochMs - failedAtEpochMs
        if (elapsedMs < 0L) return false
        return elapsedMs >= simklSyncFailureBackoffMs(consecutiveSyncFailures)
    }
    if (lastCheckedAtEpochMs == null) return true

    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

internal typealias SimklRefreshGate = com.nuvio.app.features.tracking.TrackingRefreshGate
internal typealias SimklRefreshGateOutcome = com.nuvio.app.features.tracking.TrackingRefreshGateOutcome
