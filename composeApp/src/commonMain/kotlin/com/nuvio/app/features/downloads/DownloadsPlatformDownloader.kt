package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
    /**
     * Drop any validator (ETag/If-Range) inherited from a previous transfer
     * instead of reusing it. Set when the item's source URL was swapped for a
     * freshly resolved one: a stale validator must never be sent against a
     * different URL.
     */
    val resetValidator: Boolean = false,
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
}

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    /**
     * Synchronously deletes the `.part` file for [destinationFileName], if any.
     * Unlike [removePartialFile], this never touches the transfer store: use it
     * when swapping a download's source URL, where the partial belongs to the
     * old link and must be gone before the new transfer starts.
     */
    fun deletePartialFileNow(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean
}
