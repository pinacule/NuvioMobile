package com.nuvio.app.features.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.nuvio.app.core.device.DeviceEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "DownloadWifiWatcher"

/**
 * Enforces the Wi-Fi-only download rule while the process lives.
 *
 * Registered once per process from [DownloadsPlatformDownloader.scheduler],
 * which runs in the app process (MainActivity init), in job/worker
 * processes, and in the notification-action receiver process — so the
 * watcher is present wherever a transfer can run. It observes the DEFAULT
 * network (the one traffic actually uses) and re-evaluates the transport
 * snapshot on every callback, rather than trusting individual callback
 * ordering during a Wi-Fi→cellular handover:
 *
 * - Wi-Fi lost (or the active network becomes cellular/offline/unknown):
 *   every running transfer is parked as waiting for Wi-Fi, first in the
 *   transfer store ([AndroidDownloadScheduler.pauseForWifi], which also
 *   cancels the scheduled job and therefore the running transfer's
 *   coroutine), then in the repository (which owns the UI/notification
 *   copy). Progress already downloaded is preserved in the partial file.
 * - Wi-Fi (re)gained: parked downloads resume automatically, with no user
 *   action. User-paused downloads are never flagged, so they never resume
 *   here.
 *
 * The initial callback fires for the current network on registration, so
 * app start on Wi-Fi resumes parked downloads and app start on cellular
 * leaves them parked, with no separate startup check.
 *
 * Deliberately NOT debounced: the rule is absolute ("never download on
 * data"), so a pause must land the moment Wi-Fi is gone, even if the
 * network flaps. Resume re-prepares a torrent stream / Range-resumes an
 * HTTP partial, both cheap, so a flap costs little.
 *
 * Limitation: when no app process is alive (app fully closed, no job
 * running), nothing observes the network, so a Wi-Fi return only resumes
 * parked downloads the next time a process starts — opening the app, or
 * any other download activity. No new background job is scheduled for
 * this: JobScheduler has no Wi-Fi-only constraint, and an ANY-network
 * watcher job would wake the process on cellular too.
 */
internal object DownloadWifiWatcher {
    private var registered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize(context: Context) {
        if (registered) return
        runCatching {
            val connectivityManager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = evaluate()
                    override fun onLost(network: Network) = evaluate()
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                        evaluate()
                },
            )
            registered = true
        }.onFailure { error ->
            // Stay unregistered so a later call can retry; the start gates
            // (repository, job service, worker) still block new transfers
            // off Wi-Fi, only mid-transfer enforcement is lost if the
            // callback never registers.
            registered = false
            Log.w(TAG, "Could not register download Wi-Fi watcher", error)
        }
    }

    private fun evaluate() {
        scope.launch {
            runCatching {
                if (DeviceEnvironment.isWifi()) {
                    DownloadsRepository.resumeWifiWaitingDownloads()
                } else {
                    // Store first: cancelling the scheduled job stops the
                    // running transfer; the repository step then only flips
                    // UI state (its handle-cancel no-ops on the store side,
                    // since the store is already Paused).
                    DownloadsPlatformDownloader.schedulerOrNull()?.let { scheduler ->
                        scheduler.store.transfers.value.values
                            .filter { it.item.status == DownloadStatus.Downloading }
                            .forEach { scheduler.pauseForWifi(it.item.fileName) }
                    }
                    DownloadsRepository.pauseDownloadsForWifi()
                }
            }.onFailure { error ->
                Log.w(TAG, "Download Wi-Fi evaluation failed", error)
            }
        }
    }
}
