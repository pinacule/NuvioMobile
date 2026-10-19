package com.nuvio.app.features.downloads

import android.content.Context
import com.nuvio.app.features.p2p.NuvioEngineHost
import com.nuvio.app.features.p2p.P2pSettingsStorage
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Android silent preload cache.
 *
 * Reuses the regular download transfer functions ([transferTorrentDownload] /
 * [transferAndroidDownload]) but writes into `<cacheDir>/preload-cache` instead
 * of the downloads dir. No scheduler, no WorkManager job, no notifications and
 * no persisted transfer: the fetch runs in the caller's coroutine and is
 * cancelled by cancelling it (the partial `.part` file stays for resume).
 */
internal actual object PreloadCachePlatform {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        P2pSettingsStorage.initialize(context.applicationContext)
        NuvioEngineHost.initialize(context.applicationContext)
    }

    private fun cacheDir(): File {
        val context = checkNotNull(appContext) { "PreloadCachePlatform is not initialized" }
        return File(context.cacheDir, "preload-cache")
    }

    private fun cacheDirOrNull(): File? = runCatching { cacheDir() }.getOrNull()

    actual suspend fun fetchMedia(item: DownloadItem, fileName: String): String? {
        val directory = cacheDir()
        var outputFileName = fileName
        val partial = if (item.isTorrent) {
            transferTorrentDownload(
                item = item,
                directory = directory,
                onResolvedFile = { outputFileName = it },
                onHeaders = { _, _ -> },
                onProgress = { _, _ -> },
            )
        } else {
            transferAndroidDownload(
                item = item,
                directory = directory,
                validator = null,
                onHeaders = { _, _ -> },
                onProgress = { _, _ -> },
            )
        }
        val destination = File(directory, outputFileName)
        if (partial != destination && !partial.renameTo(destination)) {
            throw IOException("Could not finalize the preloaded file")
        }
        return destination.toURI().toString()
    }

    actual fun cachedFileExists(localFileUri: String): Boolean {
        val file = runCatching { File(URI(localFileUri)) }.getOrNull() ?: return false
        return file.isFile
    }

    actual fun deleteCachedFile(localFileUri: String) {
        val dir = cacheDirOrNull() ?: return
        val file = runCatching { File(URI(localFileUri)) }.getOrNull() ?: return
        // Only touch files inside our own cache dir.
        if (!file.canonicalPath.startsWith(dir.canonicalPath)) return
        file.delete()
        File(file.parentFile, "${file.name}.part").delete()
    }

    actual fun clearCache() {
        cacheDirOrNull()
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.forEach { it.deleteRecursively() }
    }
}
