package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.resume

/**
 * iOS silent preload cache. HTTP(S) only — torrent fetches are not supported on
 * iOS (same as regular downloads) and return null so playback falls back to
 * streaming.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PreloadCachePlatform {

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
        val destPath = "${cacheDir()}/$fileName"
        val location: NSURL? = suspendCancellableCoroutine { cont ->
            val nativeRequest = NSMutableURLRequest(
                uRL = NSURL(string = item.sourceUrl),
                cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
                timeoutInterval = 60.0,
            )
            nativeRequest.setHTTPMethod("GET")
            item.sourceHeaders.forEach { (key, value) ->
                nativeRequest.setValue(value, forHTTPHeaderField = key)
            }
            val task = NSURLSession.sharedSession.downloadTaskWithRequest(nativeRequest) { loc, _, error ->
                if (error != null) cont.resume(null) else cont.resume(loc)
            }
            cont.invokeOnCancellation { task.cancel() }
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

    actual fun clearCache() {
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(cacheDir(), null)
            ?.filterIsInstance<String>()
            ?.forEach { NSFileManager.defaultManager.removeItemAtPath("$cacheDir()/$it", null) }
    }

    private fun String.toCachePath(): String? =
        NSURL(string = this)?.path ?: takeIf { startsWith("/") }
}
