package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.features.streams.redactedForLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URL

/**
 * Downloads subtitle files from remote URLs to local cache and provides
 * content:// URIs via FileProvider so external players can read them.
 */
object SubtitleFileCache {
    private const val TAG = "SubtitleFileCache"
    private const val SUBTITLE_CACHE_DIR = "subtitles"

    /** Hard cap on a downloaded subtitle body; see downloadHttpSubtitleToFile. */
    private const val MAX_SUBTITLE_BYTES = 8L * 1024L * 1024L
    private const val MAX_SUBTITLE_REDIRECTS = 3

    private var appContext: Context? = null
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            // Redirects are followed manually in downloadHttpSubtitleToFile
            // so the hop count stays bounded (MAX_SUBTITLE_REDIRECTS).
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val cacheDir: File?
        get() = appContext?.let { File(it.cacheDir, SUBTITLE_CACHE_DIR).also { dir -> dir.mkdirs() } }

    /**
     * Downloads subtitle files and returns updated [SubtitleInput] list with
     * content:// URIs instead of HTTP URLs.
     *
     * Subtitles that fail to download are silently skipped (not included in result).
     * Returns null if no subtitles could be downloaded.
     */
    suspend fun cacheSubtitles(subtitles: List<SubtitleInput>): List<SubtitleInput>? {
        if (subtitles.isEmpty()) return null
        val context = appContext ?: return null

        // Clean old cached subtitles before downloading new ones
        clearCache()

        val cached = subtitles.mapNotNull { input ->
            try {
                val localUri = downloadToCache(context, input)
                if (localUri != null) {
                    input.copy(url = localUri.toString())
                } else {
                    Log.w(TAG, "Failed to download subtitle: ${input.name}")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error caching subtitle: ${input.name}", e)
                null
            }
        }

        return cached.ifEmpty { null }
    }

    /**
     * Downloads a single subtitle file and returns a content:// URI.
     */
    private suspend fun downloadToCache(context: Context, input: SubtitleInput): Uri? =
        withContext(Dispatchers.IO) {
            val dir = cacheDir ?: return@withContext null
            val extension = guessExtension(input.url)
            val filename = sanitizeFilename("${input.lang}_${input.name}.$extension")
            val file = File(dir, filename)

            try {
                if (input.url.startsWith("file:")) {
                    File(URI(input.url)).copyTo(file, overwrite = true)
                    return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                if (!downloadHttpSubtitleToFile(input.url, file)) {
                    file.delete()
                    return@withContext null
                }

                // Return content:// URI via FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download subtitle file: ${input.url.redactedForLogs()}", e)
                file.delete()
                null
            }
        }

    /**
     * Fetches [initialUrl] into [target], following at most
     * [MAX_SUBTITLE_REDIRECTS] redirects. Redirects may cross hosts —
     * subtitle providers commonly hand off from their API host to a
     * separate download host (or a subdomain of it) — and this fetch
     * attaches no credential headers, so a cross-host hop leaks nothing.
     * The body is capped
     * at [MAX_SUBTITLE_BYTES]: the fetch aborts (and the caller deletes the
     * partial file) rather than letting a hostile or broken URL fill the disk.
     * Real subtitles, including font-heavy ASS, are a few MB at most; the cap
     * sits well above that while staying under the codebase's 16 MB API cap.
     */
    private fun downloadHttpSubtitleToFile(initialUrl: String, target: File): Boolean {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val nextUrl = try {
                val request = Request.Builder().url(currentUrl).build()
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body ?: return false
                            if (body.contentLength() > MAX_SUBTITLE_BYTES) {
                                Log.w(TAG, "Subtitle exceeds the size cap: ${currentUrl.redactedForLogs()}")
                                return false
                            }
                            return copySubtitleBodyCapped(body.byteStream(), target)
                        }
                        response.code in 300..399 -> {
                            val location = response.header("Location")
                                ?.takeIf { it.isNotBlank() } ?: return false
                            if (redirects >= MAX_SUBTITLE_REDIRECTS) return false
                            runCatching { URL(URL(currentUrl), location).toString() }
                                .getOrNull() ?: return false
                        }
                        else -> {
                            Log.w(TAG, "HTTP ${response.code} downloading subtitle: ${currentUrl.redactedForLogs()}")
                            return false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed subtitle fetch: ${currentUrl.redactedForLogs()}", e)
                return false
            }
            currentUrl = nextUrl
            redirects += 1
        }
    }

    /** Streams [input] into [target], returning false once the size cap is exceeded. */
    private fun copySubtitleBodyCapped(input: InputStream, target: File): Boolean {
        var copiedBytes = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                copiedBytes += read
                if (copiedBytes > MAX_SUBTITLE_BYTES) {
                    Log.w(TAG, "Subtitle download exceeded the ${MAX_SUBTITLE_BYTES}-byte cap; aborting")
                    return false
                }
                output.write(buffer, 0, read)
            }
        }
        return true
    }

    /**
     * Removes all cached subtitle files.
     */
    fun clearCache() {
        try {
            cacheDir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear subtitle cache", e)
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            path.endsWith(".vtt", ignoreCase = true) -> "vtt"
            path.endsWith(".ass", ignoreCase = true) -> "ass"
            path.endsWith(".ssa", ignoreCase = true) -> "ssa"
            path.endsWith(".ttml", ignoreCase = true) -> "ttml"
            path.endsWith(".dfxp", ignoreCase = true) -> "dfxp"
            else -> "srt"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(100) // Limit filename length
    }
}
