package com.nuvio.app.features.p2p

/**
 * How long the player may starve with zero delivery progress before a
 * torrent stream counts as stalled.
 */
internal const val P2P_STALL_WINDOW_MS = 45_000L

/** Sampling period for the player-side stall watchdog. */
internal const val P2P_STALL_SAMPLE_INTERVAL_MS = 5_000L

/**
 * Progress-based stall detection for torrent playback (audit P1).
 *
 * Neither existing safety net catches a dead-but-chatty swarm: the engine
 * is configured with `streamInactivityTimeoutMilliseconds = 0` (the
 * setting exists precisely because engine-level inactivity cannot be
 * trusted mid-stream), the downloads path's 10-minute stall timeout is
 * far too slow for interactive playback, and the loopback read timeout
 * only bounds TOTAL silence on a single read — a swarm that trickles a
 * few bytes and never fills the buffer keeps every read alive forever.
 *
 * This tracker instead watches the two delivery signals the engine
 * reports — bytes delivered to the player and buffer progress — and
 * declares a stall when the player is starving (buffering, not ended)
 * yet neither signal has advanced for [P2P_STALL_WINDOW_MS]. 45 s is far
 * beyond a healthy swarm's burst gaps and equals the player's maximum
 * buffered-ahead window (45 s of video), so a starving player with zero
 * delivery for that long is being fed by nobody.
 *
 * Pure logic — unit-tested in commonTest. Not thread-safe; callers use
 * it from a single coroutine.
 */
internal class P2pStallTracker(
    private val stallWindowMs: Long = P2P_STALL_WINDOW_MS,
) {
    private var maxDeliveredBytes: Long = Long.MIN_VALUE
    private var maxBufferProgress: Float = Float.NEGATIVE_INFINITY
    private var lastProgressAtMs: Long = Long.MIN_VALUE

    fun reset() {
        maxDeliveredBytes = Long.MIN_VALUE
        maxBufferProgress = Float.NEGATIVE_INFINITY
        lastProgressAtMs = Long.MIN_VALUE
    }

    /**
     * Feeds one sample and returns true when the stall window has
     * elapsed. [starving] is the player state: while the player is not
     * starving — playing from buffer, paused with a full-enough buffer,
     * or already showing an error — the no-progress clock is held at
     * [nowMs], so a legitimate pause can never trip the watchdog.
     * Progress on EITHER signal (delivered bytes or buffer progress)
     * re-arms the window; only the best values seen are kept, so a
     * counter that jitters backwards cannot fake progress.
     */
    fun onSample(
        nowMs: Long,
        deliveredBytes: Long,
        bufferProgress: Float,
        starving: Boolean,
    ): Boolean {
        if (lastProgressAtMs == Long.MIN_VALUE) {
            // First sample only establishes the baseline.
            maxDeliveredBytes = deliveredBytes
            maxBufferProgress = bufferProgress
            lastProgressAtMs = nowMs
            return false
        }
        val progressed = deliveredBytes > maxDeliveredBytes || bufferProgress > maxBufferProgress
        if (deliveredBytes > maxDeliveredBytes) maxDeliveredBytes = deliveredBytes
        if (bufferProgress > maxBufferProgress) maxBufferProgress = bufferProgress
        if (progressed || !starving) {
            lastProgressAtMs = nowMs
            return false
        }
        return nowMs - lastProgressAtMs >= stallWindowMs
    }
}
