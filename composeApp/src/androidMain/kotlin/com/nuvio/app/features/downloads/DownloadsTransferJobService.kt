package com.nuvio.app.features.downloads

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

@RequiresApi(34)
class DownloadsTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<String, Job>()

    override fun onStartJob(params: JobParameters): Boolean = startTransfer(params)

    override fun onNetworkChanged(params: JobParameters) {
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return
        // Torrent transfers read from the engine's loopback server and the engine manages its own sockets,
        // so restarting them on a network change would only drop their peers.
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME)
        val transfer = fileName?.let { DownloadsPlatformDownloader.scheduler(this).store.get(it) }
        if (transfer?.item?.isTorrent == true) return
        if (runs[generation]?.isActive == true) startTransfer(params)
    }

    private fun startTransfer(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return false
        if (!scheduler.isActive(transfer)) return false
        DownloadsLiveStatusPlatform.initialize(this)
        setNotification(params, DownloadsLiveStatusPlatform.notificationId(transfer.item.id),
            DownloadsLiveStatusPlatform.buildNotification(this, transfer.item), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Only execute() is guarded: an exception escaping it (e.g.
                // recording a failure on a full disk) must not skip
                // jobFinished or the failure notification below. Mirrors the
                // catch in DownloadsTransferWorker.doWork.
                var retry = false
                try {
                    retry = scheduler.execute(transfer, params.network) { updated ->
                        updated.item.totalBytes?.let { updateEstimatedNetworkBytes(params, it, 0L) }
                        updateTransferredNetworkBytes(params, updated.item.downloadedBytes, 0L)
                        DownloadsLiveStatusPlatform.notifyTransfer(updated.item)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // execute() records its own terminal failures; this is
                    // for failures of that recording itself. fail() is
                    // best-effort and does not throw; runCatching is
                    // belt-and-braces so jobFinished is always reached.
                    runCatching { scheduler.fail(transfer, error) }
                }
                jobFinished(params, retry)
                runCatching { scheduler.notifyCurrent(fileName) }
            } finally {
                if (runs[generation] === currentCoroutineContext()[Job]) runs.remove(generation)
            }
        }
        runs.put(generation, run)?.cancel()
        run.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        runs.remove(generation)?.cancel()
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return false
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            scheduler.pause(fileName)
            return false
        }
        return scheduler.isActive(transfer)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
