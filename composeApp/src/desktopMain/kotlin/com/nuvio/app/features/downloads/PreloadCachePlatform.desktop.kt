package com.nuvio.app.features.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/**
 * Desktop silent preload cache. HTTP(S) only — torrent fetches are not
 * supported on desktop (same as regular downloads) and return null so playback
 * falls back to streaming.
 */
internal actual object PreloadCachePlatform {

    private val client by lazy {
        HttpClient(OkHttp) {
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    private fun cacheDir(): File {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".config").absolutePath
        return File(File(base, "nuvio"), "preload-cache")
    }

    actual suspend fun fetchMedia(item: DownloadItem, fileName: String): String? {
        if (item.isTorrent) return null
        if (!startupSweepDone) {
            // The registry is in-memory only: anything on disk from a previous
            // run is orphaned. One-episode lookahead, so a sweep is safe.
            startupSweepDone = true
            clearCache()
        }
        val dir = cacheDir().apply { mkdirs() }
        val destFile = File(dir, fileName)
        val partFile = File(dir, "$fileName.part")
        val resumeFrom = partFile.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
        return withContext(Dispatchers.IO) {
            val response: HttpResponse = client.get(item.sourceUrl) {
                item.sourceHeaders.forEach { (key, value) -> header(key, value) }
                if (resumeFrom > 0L) header(HttpHeaders.Range, "bytes=$resumeFrom-")
            }
            if (resumeFrom > 0L && response.status.value != 206) {
                // The server ignored our Range request: appending its full
                // response to the partial file would corrupt it. Discard the
                // partial and restart from zero (resumeFrom is 0 then, so this
                // cannot recurse).
                partFile.delete()
                return@withContext fetchMedia(item, fileName)
            }
            if (!response.status.isSuccess()) return@withContext null
            val channel = response.bodyAsChannel()
            FileOutputStream(partFile, resumeFrom > 0L).use { out ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    ensureActive()
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
                out.fd.sync()
            }
            ensureActive()
            if (!partFile.renameTo(destFile)) return@withContext null
            destFile.toURI().toString()
        }
    }

    actual fun cachedFileExists(localFileUri: String): Boolean {
        val file = runCatching { File(URI(localFileUri)) }.getOrNull() ?: return false
        return file.isFile
    }

    actual fun deleteCachedFile(localFileUri: String) {
        val file = runCatching { File(URI(localFileUri)) }.getOrNull() ?: return
        // Only touch files inside our own cache dir.
        if (!file.canonicalPath.startsWith(cacheDir().canonicalPath)) return
        file.delete()
        File(file.parentFile, "${file.name}.part").delete()
    }

    actual fun deletePartialFile(fileName: String) {
        if (fileName.contains('/') || fileName.contains('\\')) return
        File(cacheDir(), "$fileName.part").delete()
    }

    actual fun clearCache() {
        cacheDir().takeIf { it.isDirectory }?.listFiles()?.forEach { it.deleteRecursively() }
    }

    private var startupSweepDone = false
}
