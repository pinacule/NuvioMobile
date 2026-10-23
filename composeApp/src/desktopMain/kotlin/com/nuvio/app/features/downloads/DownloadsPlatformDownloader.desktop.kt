package com.nuvio.app.features.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop downloader: plain HTTP(S) downloads via Ktor/OkHttp into the app's
 * downloads directory, with resume via Range requests. Torrent downloads are
 * disabled on desktop by policy ([AppFeaturePolicy.p2pDownloadsEnabled]).
 */
internal actual object DownloadsPlatformDownloader {
    private const val progressMinIntervalMs = 500L
    private const val progressMinByteDelta = 512L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadClient by lazy {
        HttpClient(OkHttp) {
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    private val appDataDir: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".config").absolutePath
        File(base, "nuvio")
    }

    private fun downloadsDir(): File = File(appDataDir, "downloads")

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = Job()
        val handle = DesktopDownloadsTaskHandle(job)
        scope.launch(job) {
            try {
                if (request.item.isTorrent) {
                    onFailure("Torrent downloads are not supported on desktop")
                    return@launch
                }
                val dir = downloadsDir().apply { mkdirs() }
                val safeName = File(request.destinationFileName).name
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Invalid destination file name")
                val destFile = File(dir, safeName)
                val partFile = File(dir, "$safeName.part")

                // Mirror AndroidDownloadScheduler.refreshAgedHttpSource: a link that
                // aged while the download waited/paused is re-resolved before the
                // transfer starts. The partial stays, guarded by the size check
                // below; when nothing fresh resolves the current link is kept.
                var activeItem = request.item
                var activeRequest = request
                if (activeItem.hasAgedHttpSource(DownloadsClock.nowEpochMs())) {
                    val fresh = try {
                        reResolveHttpDownloadSource(activeItem)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                    if (fresh != null) {
                        activeItem = fresh.copy(
                            status = activeItem.status,
                            downloadedBytes = activeItem.downloadedBytes,
                            totalBytes = activeItem.totalBytes,
                            errorMessage = null,
                        )
                        DownloadsRepository.refreshItemSource(activeItem)
                        activeRequest = DownloadPlatformRequest(activeItem)
                    }
                }

                DownloadSubtitles.prepare(activeItem, destFile.toURI().toString())

                var resumeFrom = partFile.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
                var sourceSwapAttempted = false
                var response = fetch(activeRequest, resumeFrom.takeIf { it > 0L })
                while (true) {
                    // Mirror AndroidDownloadScheduler.executeOnce: a stale link
                    // (403/404/410) gets ONE swap to a freshly resolved URL and an
                    // immediate retry with it, never a verbatim retry of the dead
                    // URL. Checked before the Range handling so the partial is
                    // not dropped for a link that is about to be replaced; the
                    // resumed response is size-checked below.
                    if (response.status.value in STALE_HTTP_STATUS_CODES && !sourceSwapAttempted) {
                        sourceSwapAttempted = true
                        val refreshed = try {
                            refreshHttpDownloadSource(activeItem)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        if (refreshed != null && refreshed.sourceUrl != activeItem.sourceUrl) {
                            activeItem = refreshed.copy(
                                status = DownloadStatus.Downloading,
                                downloadedBytes = activeItem.downloadedBytes,
                                totalBytes = activeItem.totalBytes,
                                errorMessage = null,
                            )
                            DownloadsRepository.refreshItemSource(activeItem)
                            activeRequest = DownloadPlatformRequest(activeItem)
                            response = fetch(activeRequest, resumeFrom.takeIf { it > 0L })
                            continue
                        }
                    }
                    // Mirror Android's isResumeSizeMismatch (androidMain, so the
                    // condition is inlined): a 206 whose Content-Range total
                    // differs from the size the partial was written for belongs
                    // to a different encode — restart from zero instead of
                    // Range-appending onto the old partial.
                    if (resumeFrom > 0L && response.status == HttpStatusCode.PartialContent) {
                        val expectedTotalBytes = activeItem.totalBytes
                        val resumedTotalBytes = parseContentRangeTotal(response.headers[HttpHeaders.ContentRange])
                        if (
                            expectedTotalBytes != null && expectedTotalBytes > 0L &&
                            resumedTotalBytes != null && resumedTotalBytes != expectedTotalBytes
                        ) {
                            partFile.delete()
                            resumeFrom = 0L
                            response = fetch(activeRequest, null)
                            continue
                        }
                    }
                    if (resumeFrom > 0L && response.status.isSuccess() && response.status != HttpStatusCode.PartialContent) {
                        // Server ignored the Range request: restart from scratch.
                        // Gated on success so a non-2xx response (e.g. a 403 the
                        // stale-link branch above could not replace) falls
                        // through to the isSuccess() failure check below with
                        // the partial intact, instead of deleting it here.
                        partFile.delete()
                        resumeFrom = 0L
                        response = fetch(activeRequest, null)
                        continue
                    }
                    break
                }
                if (!response.status.isSuccess()) {
                    onFailure("Download failed: HTTP ${response.status.value}")
                    return@launch
                }

                val total = response.contentLength()?.let { resumeFrom + it }
                streamToFile(response, partFile, resumeFrom, total, job, onProgress)

                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    Files.move(partFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                onSuccess(destFile.toURI().toString(), total ?: destFile.length())
            } catch (error: CancellationException) {
                onPaused()
            } catch (error: Exception) {
                onFailure(error.message?.takeIf { it.isNotBlank() } ?: "Download failed")
            }
        }
        return handle
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) item.copy(status = DownloadStatus.Paused) else item

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val safeName = File(destinationFileName).name
        if (safeName.isBlank()) return false
        // Mirror iOS/Android: removing a partial also removes its subtitle
        // sidecar, keyed by the destination file's URI (same construction
        // DownloadSubtitles.prepare uses for this platform).
        val destFile = File(downloadsDir(), safeName)
        DownloadSubtitleStorage(destFile.toURI().toString()).remove()
        val partFile = File(downloadsDir(), "$safeName.part")
        return !partFile.exists() || runCatching { partFile.delete() }.getOrDefault(false)
    }

    actual val keepsPartialAcrossSourceSwap: Boolean = false

    actual fun deletePartialFileNow(destinationFileName: String): Boolean =
        removePartialFile(destinationFileName)

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val localFile = File(downloadsDir(), File(fileName).name)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val dir = downloadsDir().apply { mkdirs() }
        return runCatching {
            check(java.awt.Desktop.isDesktopSupported())
            java.awt.Desktop.getDesktop().open(dir)
            true
        }.getOrDefault(false)
    }

    private suspend fun fetch(request: DownloadPlatformRequest, rangeStart: Long?): HttpResponse =
        downloadClient.get(request.sourceUrl) {
            request.sourceHeaders.forEach { (key, value) -> header(key, value) }
            if (rangeStart != null && rangeStart > 0L) {
                header(HttpHeaders.Range, "bytes=$rangeStart-")
            }
        }

    /** Total size from a `Content-Range: bytes start-end/total` header; null when absent or `*`. */
    private fun parseContentRangeTotal(headerValue: String?): Long? {
        val value = headerValue?.trim().orEmpty()
        if (value.isBlank()) return null
        val slashIndex = value.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex == value.lastIndex) return null
        val totalPart = value.substring(slashIndex + 1).trim()
        if (totalPart == "*") return null
        return totalPart.toLongOrNull()?.takeIf { it > 0L }
    }

    private suspend fun streamToFile(
        response: HttpResponse,
        partFile: File,
        resumeFrom: Long,
        total: Long?,
        job: Job,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val channel = response.bodyAsChannel()
        FileOutputStream(partFile, resumeFrom > 0L).use { out ->
            val buffer = ByteArray(32 * 1024)
            var downloaded = resumeFrom
            var lastReportMs = 0L
            var lastReportBytes = downloaded
            while (true) {
                job.ensureActive()
                val read = channel.readAvailable(buffer)
                if (read == -1) break
                if (read == 0) continue
                out.write(buffer, 0, read)
                downloaded += read
                val now = System.currentTimeMillis()
                if (now - lastReportMs >= progressMinIntervalMs ||
                    downloaded - lastReportBytes >= progressMinByteDelta
                ) {
                    onProgress(downloaded, total)
                    lastReportMs = now
                    lastReportBytes = downloaded
                }
            }
            onProgress(downloaded, total)
        }
    }

    private fun String.toLocalFileOrNull(): File? =
        runCatching {
            if (startsWith("file:")) {
                File(URI(this))
            } else {
                File(this)
            }
        }.getOrNull()

    private class DesktopDownloadsTaskHandle(private val job: Job) : DownloadsTaskHandle {
        override fun cancel() {
            job.cancel()
        }
    }
}
