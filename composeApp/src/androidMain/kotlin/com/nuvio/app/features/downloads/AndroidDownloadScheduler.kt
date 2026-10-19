package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.download_failed_link_expired
import okhttp3.ConnectionPool
import org.jetbrains.compose.resources.getString

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    val directory = File(context.filesDir, "downloads")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(item: DownloadItem, resetValidator: Boolean = false): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        val transfer = store.begin(item, resetValidator)
        val existing = previous?.generation == transfer.generation
        if (existing && Build.VERSION.SDK_INT >= 34) return transfer
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                check(jobScheduler().schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS) {
                    "Android could not start the download. Open the app and try again."
                }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to item.fileName, GENERATION to transfer.generation))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
        return store.get(item.fileName) ?: transfer
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @RequiresApi(34)
    private fun jobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && Build.VERSION.SDK_INT >= 34 &&
            jobScheduler().getPendingJob(transfer.jobId) == null) {
            pause(item.fileName)
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null))
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        val outputFileName = transfer?.outputFileName?.takeIf { it != fileName }
        store.remove(fileName)
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    File(directory, "$fileName.part").delete()
                    DownloadSubtitleStorage(File(directory, fileName).toURI().toString()).remove()
                    outputFileName?.let { name ->
                        DownloadSubtitleStorage(File(directory, name).toURI().toString()).remove()
                    }
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val torrent = transfer.item.isTorrent
        var destination = File(directory, transfer.outputFileName ?: fileName)
        // Torrent bytes come from the engine's loopback server, which a network-bound socket cannot reach.
        val client = if (network != null && !torrent) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        try {
            if (!torrent) {
                // Torrent subtitles are prepared once the real output file name is known.
                DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
            }
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            var lastProgressAt = 0L
            val onHeaders: (Long?, String?) -> Unit = { total, validator ->
                updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
            }
            val onBytes: (Long, Long?) -> Unit = { bytes, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 1_000L || bytes == total) {
                    lastProgressAt = now
                    updateActive(transfer) {
                        it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                    }?.let(onProgress)
                }
            }
            val partial = when {
                destination.isFile -> destination
                torrent -> transferTorrentDownload(
                    item = transfer.item,
                    directory = directory,
                    onResolvedFile = { outputFileName ->
                        updateActive(transfer) { it.copy(outputFileName = outputFileName) }
                        destination = File(directory, outputFileName)
                        DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
                    },
                    onHeaders = onHeaders,
                    onProgress = onBytes,
                )
                else -> transferAndroidDownload(
                    item = transfer.item,
                    directory = directory,
                    validator = transfer.validator,
                    client = client,
                    onHeaders = onHeaders,
                    onProgress = onBytes,
                )
            }
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                if (partial != destination && !partial.renameTo(destination)) {
                    throw IOException("Could not finalize the downloaded file")
                }
                val bytes = destination.length()
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = destination.toURI().toString(),
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                ))
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            if (error is DownloadHttpException &&
                error.statusCode in STALE_HTTP_STATUS_CODES &&
                transfer.item.sourceKind == DownloadSourceKind.Http &&
                transfer.sourceRefreshCount < MAX_DOWNLOAD_SOURCE_REFRESHES
            ) {
                // The link itself is dead (expired addon/debrid URL): resolve a
                // fresh one and retry with it instead of failing. Never retry
                // the same dead URL verbatim.
                val refreshed = try {
                    refreshHttpDownloadSource(transfer.item)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (refreshError: Exception) {
                    null
                }
                if (refreshed != null && refreshed.sourceUrl != transfer.item.sourceUrl) {
                    // The new link may point at a different encode: drop the
                    // partial and restart from zero rather than resuming bytes
                    // across files.
                    File(directory, "${transfer.item.fileName}.part").delete()
                    val swapped = refreshed.copy(
                        status = DownloadStatus.Downloading,
                        downloadedBytes = 0L,
                        totalBytes = null,
                        errorMessage = null,
                    )
                    // updateActive hands the transfer back unchanged when it
                    // stopped being Downloading mid-refresh (user
                    // paused/cancelled): only propagate the new URL when the
                    // swap actually landed, or the repository's Paused copy
                    // would flip back to "Downloading" in the UI while the
                    // transfer stays paused.
                    val applied = updateActive(transfer) {
                        it.copy(
                            item = swapped,
                            validator = null,
                            sourceRefreshCount = it.sourceRefreshCount + 1,
                            retryCount = 0,
                        )
                    }
                    if (applied?.item?.sourceUrl != refreshed.sourceUrl) return@withLock false
                    // The repository owns the UI/pause-resume copy of the item:
                    // it must see the new URL too, or a later resume would
                    // restart the old dead link.
                    DownloadsRepository.refreshItemSource(swapped)
                    return@withLock true
                }
            }
            val retry = shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            else fail(transfer, error)
            retry
        } finally {
            if (client !== downloadHttpClient) {
                withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
            }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = humanDownloadError(error)))
        }
    }

    /**
     * Never park a raw engine message on the download: stream-preparation
     * failures collapse to the generic human-readable text once retries are
     * exhausted, and dead links that survived every refresh say so plainly
     * instead of flashing a bare HTTP code.
     */
    private fun humanDownloadError(error: Exception): String = runBlocking {
        when {
            error.isStreamPreparationFailure() -> getString(Res.string.download_failed)
            error is DownloadHttpException && error.statusCode in STALE_HTTP_STATUS_CODES ->
                getString(Res.string.download_failed_link_expired)
            else -> error.message ?: getString(Res.string.download_failed)
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when {
        error.isStreamPreparationFailure() -> true
        error is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        error is IOException -> true
        else -> false
    }

/**
 * The native engine rejects a second concurrent stream preparation with
 * "stream preparation already pending", and our own lock converts an
 * over-long wait into a "Timed out waiting for stream preparation" failure.
 * Both are transient: the queue must back off and try again, never park the
 * raw engine text on the download as its final state.
 */
internal fun Exception.isStreamPreparationFailure(): Boolean =
    message?.contains("stream preparation", ignoreCase = true) == true
