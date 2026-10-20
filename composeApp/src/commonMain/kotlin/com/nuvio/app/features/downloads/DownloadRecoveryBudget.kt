package com.nuvio.app.features.downloads

/**
 * One recovery layer's budget (scheduler retry, HTTP source refresh, torrent
 * stream re-preparation, repository transient retry), counted in CONSECUTIVE
 * failures without progress rather than over the download's lifetime.
 *
 * Each consumed recovery records the transferred-byte mark at that failure.
 * When a later failure happens after the transfer advanced beyond that mark,
 * the count starts over before the recovery is consumed: a long download whose
 * link expires at well-spaced points never burns out, while one that keeps
 * dying at the same byte position exhausts the budget after the cap, exactly
 * like a lifetime counter would for back-to-back failures.
 *
 * A mark above the current byte count (the partial was truncated or
 * restarted) is simply replaced by the next consumption, so progress on the
 * restarted file re-arms the budget from there.
 */
internal data class DownloadRecoveryBudget(
    val consecutiveFailures: Int = 0,
    val progressMark: Long = 0L,
) {
    /**
     * Records a failure at [transferredBytes] and returns the budget after
     * consuming one recovery, or null when [maxConsecutive] recoveries in a row
     * already happened without progress.
     */
    fun consume(transferredBytes: Long, maxConsecutive: Int): DownloadRecoveryBudget? {
        val count = if (transferredBytes > progressMark) 0 else consecutiveFailures
        if (count >= maxConsecutive) return null
        return DownloadRecoveryBudget(consecutiveFailures = count + 1, progressMark = transferredBytes)
    }
}
