package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.resume

/**
 * iOS silent preload cache. HTTP(S) only — torrent fetches are not supported on
 * iOS (same as regular downloads) and return null so playback falls back to
 * streaming.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PreloadCachePlatform {

    /**
     * Resume data captured when a download task is cancelled (e.g. on pause),
     * keyed by file name so a later fetch for the same episode can continue.
     */
    private val resumeDataByFile = mutableMapOf<String, NSData>()
    private var startupSweepDone = false

    private fun cacheDir(): String {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
        val base = (urls.firstOrNull() as? NSURL)?.path ?: (NSHomeDirectory() + "/Library/Caches")
        val dir = "$base/nuvio-preload-cache"
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    actual suspend fun fetchMedia(item: DownloadItem, fileName: String): String? {
        if (item.isTorrent) return null
        if (!startupSweepDone) {
            // The registry is in-memory only: anything on disk from a previous
            // run is orphaned. One-episode lookahead, so a sweep is safe.
            startupSweepDone = true
            clearCache()
        }
        val destPath = "${cacheDir()}/$fileName"
        val resumeData = resumeDataByFile.remove(fileName)
        val location: NSURL? = suspendCancellableCoroutine { cont ->
            val task: NSURLSessionDownloadTask = if (resumeData != null) {
                NSURLSession.sharedSession.downloadTaskWithResumeData(resumeData) { loc, _, error ->
                    if (error != null) cont.resume(null) else cont.resume(loc)
                }
            } else {
                val nativeRequest = NSMutableURLRequest(
                    uRL = NSURL(string = item.sourceUrl),
                    cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
                    timeoutInterval = 60.0,
                )
                nativeRequest.setHTTPMethod("GET")
                item.sourceHeaders.forEach { (key, value) ->
                    nativeRequest.setValue(value, forHTTPHeaderField = key)
                }
                NSURLSession.sharedSession.downloadTaskWithRequest(nativeRequest) { loc, _, error ->
                    if (error != null) cont.resume(null) else cont.resume(loc)
                }
            }
            cont.invokeOnCancellation {
                task.cancelByProducingResumeData { data ->
                    if (data != null) resumeDataByFile[fileName] = data
                }
            }
            task.resume()
        } ?: return null
        val moved = NSFileManager.defaultManager.moveItemAtURL(
            srcURL = location,
            toURL = NSURL.fileURLWithPath(destPath),
            error = null,
        )
        if (!moved) return null
        return NSURL.fileURLWithPath(destPath).absoluteString ?: "file://$destPath"
    }

    actual fun cachedFileExists(localFileUri: String): Boolean {
        val path = localFileUri.toCachePath() ?: return false
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    actual fun deleteCachedFile(localFileUri: String) {
        val path = localFileUri.toCachePath() ?: return
        // Only touch files inside our own cache dir.
        if (!path.startsWith(cacheDir())) return
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    actual fun deletePartialFile(fileName: String) {
        // iOS downloads go to a system temp location (no .part files); there is
        // nothing to delete, but drop any stale resume data for the file.
        if (fileName.contains("/") || fileName.contains("\\")) return
        resumeDataByFile.remove(fileName)
    }

    actual fun clearCache() {
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(cacheDir(), null)
            ?.filterIsInstance<String>()
            ?.forEach { NSFileManager.defaultManager.removeItemAtPath("$cacheDir()/$it", null) }
    }

    private fun String.toCachePath(): String? =
        NSURL(string = this)?.path ?: takeIf { startsWith("/") }
}
