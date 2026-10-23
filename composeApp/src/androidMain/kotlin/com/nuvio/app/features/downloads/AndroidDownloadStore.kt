package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AndroidDownloadTransfer(
    val item: DownloadItem,
    val jobId: Int,
    val generation: String,
    val validator: String? = null,
    /** Scheduler retries in a row without progress; see [retryBudget]. */
    val retryCount: Int = 0,
    /** Bytes on disk at the last consumed scheduler retry. */
    val retryProgressMark: Long = 0L,
    /** Dead HTTP links swapped for freshly resolved ones in a row without progress; see [sourceRefreshBudget]. */
    val sourceRefreshCount: Int = 0,
    /** Bytes on disk at the last consumed source refresh. */
    val sourceRefreshProgressMark: Long = 0L,
    /** Final file name when it differs from [DownloadItem.fileName], e.g. a torrent's real extension. */
    val outputFileName: String? = null,
    /**
     * The raw message of the error that failed this transfer. [DownloadItem.errorMessage] holds
     * the humanized text for the UI ("the stream link expired"), which no longer says whether the
     * failure was transient; the repository's retry safety net reads this instead.
     */
    val failureDetail: String? = null,
) {
    val retryBudget: DownloadRecoveryBudget
        get() = DownloadRecoveryBudget(retryCount, retryProgressMark)

    val sourceRefreshBudget: DownloadRecoveryBudget
        get() = DownloadRecoveryBudget(sourceRefreshCount, sourceRefreshProgressMark)

    fun withRetryBudget(budget: DownloadRecoveryBudget): AndroidDownloadTransfer =
        copy(retryCount = budget.consecutiveFailures, retryProgressMark = budget.progressMark)

    fun withSourceRefreshBudget(budget: DownloadRecoveryBudget): AndroidDownloadTransfer =
        copy(sourceRefreshCount = budget.consecutiveFailures, sourceRefreshProgressMark = budget.progressMark)

    /** The failure text reported to the repository: the raw error when known, else the humanized one. */
    val failureCallbackMessage: String
        get() = failureDetail?.takeIf { it.isNotBlank() } ?: item.errorMessage ?: "Download failed"
}

internal class AndroidDownloadStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val state = MutableStateFlow(load())
    val transfers = state.asStateFlow()

    fun get(fileName: String): AndroidDownloadTransfer? = state.value[fileName]

    fun begin(item: DownloadItem, resetValidator: Boolean = false): AndroidDownloadTransfer = synchronized(lock) {
        val previous = get(item.fileName)?.takeIf { it.item.id == item.id }
        if (previous?.item?.status == DownloadStatus.Downloading) return@synchronized previous
        val transfer = AndroidDownloadTransfer(
            item = item.copy(status = DownloadStatus.Downloading, errorMessage = null, localFileUri = null),
            jobId = previous?.jobId ?: ((state.value.values.maxOfOrNull { it.jobId } ?: 0) + 1),
            generation = UUID.randomUUID().toString(),
            // A swapped-in source URL must never reuse the previous link's
            // ETag validator (If-Range) against a different URL.
            validator = if (resetValidator) null else previous?.validator,
            outputFileName = previous?.outputFileName,
        )
        save(transfer)
        transfer
    }

    fun update(
        fileName: String,
        generation: String,
        transform: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = synchronized(lock) {
        val current = get(fileName)?.takeIf { it.generation == generation } ?: return@synchronized null
        transform(current).also(::save)
    }

    /**
     * Like [update], except persistence is best-effort: the in-memory state
     * is updated first and a failed record write (e.g. a full disk) is
     * swallowed instead of propagated. Only for recording a terminal
     * failure — losing the in-memory update there would wedge the transfer
     * as "Downloading" for the rest of the session.
     */
    fun updateBestEffort(
        fileName: String,
        generation: String,
        transform: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = synchronized(lock) {
        val current = get(fileName)?.takeIf { it.generation == generation } ?: return@synchronized null
        val updated = transform(current)
        state.value = state.value + (fileName to updated)
        runCatching { save(updated) }
        updated
    }

    fun remove(fileName: String) = synchronized(lock) {
        AtomicFile(recordFile(fileName)).delete()
        state.value = state.value - fileName
    }

    private fun save(transfer: AndroidDownloadTransfer) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download state directory" }
        val file = AtomicFile(recordFile(transfer.item.fileName))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(transfer).toByteArray())
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        state.value = state.value + (transfer.item.fileName to transfer)
    }

    private fun load(): Map<String, AndroidDownloadTransfer> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(it.path.removeSuffix(".bak")) }
        .distinct()
        .mapNotNull { file ->
            runCatching {
                json.decodeFromString<AndroidDownloadTransfer>(AtomicFile(file).readFully().decodeToString())
            }.getOrNull()
        }.associateBy { it.item.fileName }

    private fun recordFile(fileName: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(fileName.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
}
