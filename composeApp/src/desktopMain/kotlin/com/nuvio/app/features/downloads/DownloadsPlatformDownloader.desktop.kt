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

                DownloadSubtitles.prepare(request.item, destFile.toURI().toString())

                var resumeFrom = partFile.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
                var response = fetch(request, resumeFrom.takeIf { it > 0L })
                if (resumeFrom > 0L && response.status != HttpStatusCode.PartialContent) {
                    // Server ignored the Range request: restart from scratch.
                    partFile.delete()
                    resumeFrom = 0L
                    response = fetch(request, null)
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
        val partFile = File(downloadsDir(), "$safeName.part")
        return !partFile.exists() || runCatching { partFile.delete() }.getOrDefault(false)
    }

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
