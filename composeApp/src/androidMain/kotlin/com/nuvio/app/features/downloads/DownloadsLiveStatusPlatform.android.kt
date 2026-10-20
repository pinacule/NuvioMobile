package com.nuvio.app.features.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal actual object DownloadsLiveStatusPlatform {
    private const val channelId = "downloads_live_status"
    private const val outcomesChannelId = "downloads_outcomes"
    private const val notificationsPrefName = "nuvio_download_live_notifications"
    private const val trackedDownloadIdsKey = "tracked_download_ids"

    private var appContext: Context? = null
    private val lastRenderStateById = mutableMapOf<String, RenderState>()

    /**
     * Last status seen per download id, for terminal-outcome detection
     * (see downloadTerminalOutcome). In-memory only: after a process
     * restart every item is a first sighting and never notifies.
     */
    private val lastKnownStatusById = mutableMapOf<String, DownloadStatus>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNotificationChannel()
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return

        val manager = NotificationManagerCompat.from(context)
        val trackedBefore = preferences(context)
            .getStringSet(trackedDownloadIdsKey, emptySet())
            .orEmpty()
            .toMutableSet()

        val activeItems = items.filter { item ->
            item.status == DownloadStatus.Downloading ||
                item.status == DownloadStatus.Paused ||
                item.status == DownloadStatus.Failed
        }
        val managedIds = DownloadsPlatformDownloader.managedTransfers().map { it.item.id }.toSet()

        val trackedNow = managedIds.toMutableSet()
        activeItems.filterNot { it.id in managedIds }.forEach { item ->
            val renderState = RenderState(
                status = item.status,
                progressPercent = progressPercent(item),
                downloadedBucket = item.downloadedBytes / (512L * 1024L),
                totalBytes = item.totalBytes,
                errorMessage = item.errorMessage,
            )

            val existingState = lastRenderStateById[item.id]
            if (existingState == renderState) {
                trackedNow += item.id
                return@forEach
            }

            manager.notify(notificationId(item.id), buildNotification(context, item))
            lastRenderStateById[item.id] = renderState
            trackedNow += item.id
        }

        val staleIds = trackedBefore - trackedNow
        staleIds.forEach { downloadId ->
            manager.cancel(notificationId(downloadId))
            lastRenderStateById.remove(downloadId)
        }

        preferences(context)
            .edit()
            .putStringSet(trackedDownloadIdsKey, trackedNow)
            .apply()

        // Terminal outcomes: a download that just finished gets its own
        // dismissible notification (its progress notification was cancelled
        // above as stale, or removed by notifyTransfer). Terminal failures
        // are already surfaced by the Failed rendering above and in
        // notifyTransfer, so only Finished posts here — posting Failed too
        // would double-notify.
        val currentIds = items.mapTo(HashSet()) { it.id }
        (lastKnownStatusById.keys - currentIds).forEach { removedId ->
            // Deleted or replaced downloads lose their outcome notification.
            manager.cancel(outcomeNotificationId(removedId))
            lastKnownStatusById.remove(removedId)
        }
        items.forEach { item ->
            if (downloadTerminalOutcome(lastKnownStatusById[item.id], item.status) ==
                DownloadTerminalOutcome.Finished
            ) {
                postFinishedNotification(context, manager, item)
            }
            lastKnownStatusById[item.id] = item.status
        }
    }

    internal fun notifyTransfer(item: DownloadItem) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (item.status == DownloadStatus.Completed) {
            // Terminal success: clear the progress notification and post a
            // dismissible "finished" one. This is the only completion signal
            // when the transfer ran in a background process (worker / job
            // service) where DownloadsRepository was never loaded; in the app
            // process onItemsChanged posts the same notification id, so the
            // second post silently replaces the first.
            removeNotification(item.id)
            postFinishedNotification(context, manager, item)
        } else {
            val active = item.status == DownloadStatus.Downloading
            manager.cancel(notificationId(if (active) "inactive:${item.id}" else item.id))
            manager.notify(notificationId(if (active) item.id else "inactive:${item.id}"), buildNotification(context, item))
        }
    }

    internal fun removeNotification(downloadId: String) {
        val context = appContext ?: return
        NotificationManagerCompat.from(context).apply {
            cancel(notificationId(downloadId))
            cancel(notificationId("inactive:$downloadId"))
            cancel(outcomeNotificationId(downloadId))
        }
    }

    private fun outcomeNotificationId(downloadId: String): Int =
        notificationId("outcome:$downloadId")

    private fun postFinishedNotification(
        context: Context,
        manager: NotificationManagerCompat,
        item: DownloadItem,
    ) {
        manager.notify(outcomeNotificationId(item.id), buildFinishedNotification(context, item))
    }

    private fun buildFinishedNotification(context: Context, item: DownloadItem): android.app.Notification {
        val completedText = runBlocking { getString(Res.string.downloads_live_completed) }
        val detail = listOfNotNull(
            episodeLabel(item),
            item.displaySubtitle.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val text = if (detail.isBlank()) completedText else "$detail · $completedText"
        // Finished downloads alert on the outcomes channel (sound +
        // heads-up); PRIORITY_HIGH covers pre-O devices without channels.
        return NotificationCompat.Builder(context, outcomesChannelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(item.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(buildLaunchPendingIntent(context, item))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    /** "S01E03" for episode downloads, null for movies. */
    private fun episodeLabel(item: DownloadItem): String? {
        val season = item.seasonNumber ?: return null
        val episode = item.episodeNumber ?: return null
        return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    }

    internal fun buildNotification(context: Context, item: DownloadItem): android.app.Notification {
        val subtitle = buildSubtitle(item)
        val launchPendingIntent = buildLaunchPendingIntent(context, item)

        // Terminal failures alert on the outcomes channel; progress and
        // paused notifications stay silent on the live-status channel.
        val builderChannelId =
            if (item.status == DownloadStatus.Failed) outcomesChannelId else channelId
        val notificationBuilder = NotificationCompat.Builder(context, builderChannelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(item.title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOnlyAlertOnce(true)
            .setContentIntent(launchPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (item.status) {
            DownloadStatus.Downloading -> {
                notificationBuilder
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.compose_action_pause) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionPause,
                            downloadId = item.id,
                            fileName = item.fileName,
                        ),
                    )

                val progress = progressPercent(item)
                if (progress >= 0) {
                    notificationBuilder.setProgress(100, progress, false)
                } else {
                    notificationBuilder.setProgress(100, 0, true)
                }
            }

            DownloadStatus.Paused,
            DownloadStatus.Failed,
            DownloadStatus.Completed,
            -> {
                notificationBuilder
                    .setOngoing(false)
                    .setAutoCancel(false)
                    .setPriority(
                        if (item.status == DownloadStatus.Failed) {
                            NotificationCompat.PRIORITY_HIGH
                        } else {
                            NotificationCompat.PRIORITY_LOW
                        },
                    )
                    .setProgress(0, 0, false)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.action_resume) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionResume,
                            downloadId = item.id,
                            fileName = item.fileName,
                        ),
                    )
            }
        }

        return notificationBuilder.build()
    }

    private fun buildLaunchPendingIntent(context: Context, item: DownloadItem): PendingIntent {
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(item.id),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildSubtitle(item: DownloadItem): String {
        val detail = item.displaySubtitle
        return when (item.status) {
            DownloadStatus.Downloading -> {
                val downloaded = formatBytes(item.downloadedBytes)
                val total = item.totalBytes?.let(::formatBytes)
                val progress = if (total != null) {
                    runBlocking { getString(Res.string.downloads_live_downloading_with_total, detail, downloaded, total) }
                } else {
                    runBlocking { getString(Res.string.downloads_live_downloading, detail, downloaded) }
                }
                torrentSwarmLine(item)?.let { swarm -> "$swarm\n$progress" } ?: progress
            }

            DownloadStatus.Paused -> runBlocking { getString(Res.string.downloads_live_paused, detail) }
            DownloadStatus.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: runBlocking { getString(Res.string.downloads_live_failed) }
            DownloadStatus.Completed -> runBlocking { getString(Res.string.downloads_live_completed) }
        }
    }

    private fun torrentSwarmLine(item: DownloadItem): String? {
        if (!item.isTorrent) return null
        val swarm = TorrentDownloadSwarmRepository.swarmFor(item.id) ?: return null
        if (swarm.phase == TorrentDownloadPhase.Queued) {
            return runBlocking { getString(Res.string.downloads_torrent_queued) }
        }
        if (swarm.phase == TorrentDownloadPhase.FindingPeers) {
            return runBlocking { getString(Res.string.downloads_torrent_finding_peers) }
        }
        if (P2pSettingsRepository.uiState.value.hideTorrentStats) return null
        // Peer/seed counts are engine-global (every torrent the engine serves), not per-episode,
        // so only this episode's own speed is shown here — same as the Downloads screen card.
        return formatP2pSpeed(swarm.downloadSpeedBytesPerSecond)
    }

    private fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val units = runBlocking {
            arrayOf(
                getString(Res.string.unit_bytes_b),
                getString(Res.string.unit_bytes_kb),
                getString(Res.string.unit_bytes_mb),
                getString(Res.string.unit_bytes_gb),
                getString(Res.string.unit_bytes_tb),
            )
        }
        var value = safe
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "${"%.1f".format(value)} ${units[unitIndex]}"
        }
    }

    private fun progressPercent(item: DownloadItem): Int {
        val total = item.totalBytes?.takeIf { it > 0L } ?: return -1
        return ((item.downloadedBytes.toDouble() / total.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun buildActionPendingIntent(
        context: Context,
        action: String,
        downloadId: String,
        fileName: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadsNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadsNotificationActionReceiver.extraDownloadId, downloadId)
            putExtra(AndroidDownloadScheduler.FILE_NAME, fileName)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId("$action:$downloadId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // Channel importance is immutable once a channel exists, so each
        // channel is created independently: on upgrades the live-status
        // channel is already present while the outcomes channel is not.
        // The outcomes channel alerts (sound + heads-up) for terminal
        // finished/failed notifications; progress and paused stay silent
        // on the live-status channel.
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    runBlocking { getString(Res.string.downloads_channel_name) },
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = runBlocking { getString(Res.string.downloads_channel_description) }
                },
            )
        }
        if (manager.getNotificationChannel(outcomesChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    outcomesChannelId,
                    runBlocking { getString(Res.string.downloads_channel_name) },
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = runBlocking { getString(Res.string.downloads_channel_description) }
                },
            )
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(notificationsPrefName, Context.MODE_PRIVATE)

    internal fun notificationId(downloadId: String): Int = (downloadId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    private data class RenderState(
        val status: DownloadStatus,
        val progressPercent: Int,
        val downloadedBucket: Long,
        val totalBytes: Long?,
        val errorMessage: String?,
    )
}
