package com.nuvio.app.features.downloads

/**
 * Wi-Fi-only download rule (user requirement: downloads must NEVER transfer
 * on mobile data).
 *
 * The gate is transport-based, not billing-based: a download transfers only
 * while [DeviceEnvironment.isWifi] reports Wi-Fi (Ethernet is reported as
 * Wi-Fi-like by the platform actuals; desktop/iOS always report Wi-Fi).
 * A metered Wi-Fi hotspot therefore still transfers — the user's own SIM is
 * not used — while cellular, offline, VPN and otherwise-unknown transports
 * park the download as "waiting for Wi-Fi" instead of transferring.
 *
 * Unknown is fail-closed: when the transport cannot be determined, no
 * transfer is allowed, because "unknown" could be cellular.
 *
 * All decisions here are pure functions over a Wi-Fi snapshot and a
 * [DownloadItem], so they are unit-testable without the Android framework.
 * The Android network watcher ([DownloadWifiWatcher]) and the repository
 * both consult these, never their own ad-hoc copies of the rule.
 */
internal object DownloadWifiGate {

    /** True only when the current transport is Wi-Fi (see the class doc). */
    fun transferAllowed(isWifi: Boolean): Boolean = isWifi

    /**
     * A transfer may only start while on Wi-Fi. Anything else parks the
     * item as waiting: status Paused (never Failed — this is not an error)
     * with [DownloadItem.waitingForWifi] set, progress preserved.
     */
    fun parkAsWaiting(item: DownloadItem): DownloadItem =
        item.copy(status = DownloadStatus.Paused, waitingForWifi = true)

    /** Only a download parked by this gate, while on Wi-Fi, auto-resumes. */
    fun shouldAutoResume(item: DownloadItem, isWifi: Boolean): Boolean =
        isWifi && item.status == DownloadStatus.Paused && item.waitingForWifi

    /** A parked download whose status is still Paused keeps waiting. */
    fun isWaiting(item: DownloadItem): Boolean =
        item.status == DownloadStatus.Paused && item.waitingForWifi
}

/**
 * What the Downloads notification (and, by the same mapping, the Downloads
 * screen status label) shows for an item. The Android notification builder
 * branches on this instead of re-deriving the rule from status alone, so
 * the waiting state cannot drift from the gate above.
 */
internal enum class DownloadNotificationContent {
    /** Actively transferring: progress bar + downloaded/total text. */
    Progress,

    /** Parked by the Wi-Fi gate: "Waiting for Wi-Fi", no actions. */
    WaitingForWifi,

    /** User-paused (or scheduler-paused): resume action. */
    Paused,

    /** Terminal failure: error text, alert channel. */
    Failed,

    /** Terminal success: replaced by the finished notification. */
    Finished,
}

internal fun downloadNotificationContentFor(item: DownloadItem): DownloadNotificationContent =
    // Subject on the status enum (not a subject-less when over booleans):
    // a subject-less when used as an expression is never exhaustive without
    // an else branch, even when its conditions logically cover every case.
    // The enum subject makes exhaustiveness provable, and the waiting check
    // lives inside the Paused branch, where isWaiting can be true.
    when (item.status) {
        DownloadStatus.Downloading -> DownloadNotificationContent.Progress
        DownloadStatus.Paused ->
            if (DownloadWifiGate.isWaiting(item)) DownloadNotificationContent.WaitingForWifi
            else DownloadNotificationContent.Paused
        DownloadStatus.Failed -> DownloadNotificationContent.Failed
        DownloadStatus.Completed -> DownloadNotificationContent.Finished
    }
