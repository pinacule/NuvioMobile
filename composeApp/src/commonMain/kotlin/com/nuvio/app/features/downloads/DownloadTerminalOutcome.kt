package com.nuvio.app.features.downloads

/** A terminal download outcome that merits a user-facing notification. */
internal enum class DownloadTerminalOutcome {
    Finished,
    Failed,
}

/**
 * Decides whether a download's status change should raise a terminal-outcome
 * notification (posted by DownloadsLiveStatusPlatform on Android).
 *
 * Only genuine transitions into a terminal state qualify:
 * - First sight of an item ([previousStatus] == null) never notifies, so
 *   restoring already-finished or already-failed downloads when the app
 *   starts stays silent.
 * - Automatic retries never notify: scheduler-level retries and the
 *   repository's torrent-preparation retries keep the item in
 *   [DownloadStatus.Downloading] across attempts, and [DownloadStatus.Failed]
 *   is only parked once the retry budget is exhausted — so a
 *   Downloading -> Downloading "transition" is an attempt, not an outcome.
 * - A repeated terminal status (e.g. Failed -> Failed) is not a new outcome.
 */
internal fun downloadTerminalOutcome(
    previousStatus: DownloadStatus?,
    currentStatus: DownloadStatus,
): DownloadTerminalOutcome? = when {
    previousStatus == null || previousStatus == currentStatus -> null
    currentStatus == DownloadStatus.Completed -> DownloadTerminalOutcome.Finished
    currentStatus == DownloadStatus.Failed -> DownloadTerminalOutcome.Failed
    else -> null
}
