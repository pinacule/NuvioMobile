package com.nuvio.app.features.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nuvio.app.core.device.DeviceEnvironment

class DownloadsNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val downloadId = intent.getStringExtra(extraDownloadId)?.trim().orEmpty()
        if (downloadId.isBlank()) return

        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        DownloadsLiveStatusPlatform.initialize(context.applicationContext)
        val fileName = intent.getStringExtra(AndroidDownloadScheduler.FILE_NAME)
        val transfer = fileName?.let(scheduler.store::get)?.takeIf { it.item.id == downloadId }
        if (transfer != null) {
            when (action) {
                actionPause -> scheduler.pause(transfer.item.fileName)
                actionResume -> if (transfer.item.status == DownloadStatus.Paused || transfer.item.status == DownloadStatus.Failed) {
                    if (!DeviceEnvironment.isWifi()) {
                        // Wi-Fi-only gate: this path enqueues a job
                        // directly, bypassing the repository's start guard,
                        // so it needs its own check. Park instead of
                        // transferring; the watcher auto-resumes on Wi-Fi.
                        scheduler.pauseForWifi(transfer.item.fileName)
                        DownloadsRepository.ensureLoaded()
                        DownloadsRepository.markWaitingForWifi(downloadId)
                    } else {
                        scheduler.enqueue(transfer.item)
                        DownloadsRepository.reattachBackgroundDownload(downloadId)
                    }
                }
            }
            return
        }

        DownloadsStorage.initialize(context.applicationContext)
        DownloadsPlatformDownloader.initialize(context.applicationContext)
        DownloadsLiveStatusPlatform.initialize(context.applicationContext)
        DownloadsRepository.ensureLoaded()

        when (action) {
            actionPause -> DownloadsRepository.pauseDownload(downloadId)
            actionResume -> DownloadsRepository.resumeDownload(downloadId)
        }
    }

    companion object {
        const val actionPause = "com.nuvio.app.downloads.action.PAUSE"
        const val actionResume = "com.nuvio.app.downloads.action.RESUME"
        const val extraDownloadId = "download_id"
    }
}
