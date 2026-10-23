package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.isCredentialHeader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Tags a download request with the host its source headers were issued for. */
private class DownloadOriginHostTag(val host: String)

internal val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    // OkHttp strips Authorization on cross-host redirects but forwards every
    // other source header an addon attached (Cookie, API keys, …) to the
    // redirect target — debrid/CDN chains redirect across hosts routinely.
    // Tag each request with its origin host, then strip credential headers
    // from any hop that lands on a different host, mirroring the playback
    // client's host pinning (PlayerPlaybackNetworking).
    .addInterceptor { chain ->
        val request = chain.request()
        if (request.tag() is DownloadOriginHostTag) {
            chain.proceed(request)
        } else {
            chain.proceed(
                request.newBuilder()
                    .tag(DownloadOriginHostTag(request.url.host))
                    .build()
            )
        }
    }
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val originHost = (request.tag() as? DownloadOriginHostTag)?.host
        if (originHost == null || request.url.host.equals(originHost, ignoreCase = true)) {
            chain.proceed(request)
        } else {
            val stripped = request.newBuilder()
            request.headers.names()
                .filter { name -> isCredentialHeader(name) }
                .forEach { name -> stripped.removeHeader(name) }
            chain.proceed(stripped.build())
        }
    }
    .build()

internal class DownloadHttpException(val statusCode: Int) : IOException("Download failed: HTTP $statusCode")

/** Parallel connections one HTTP download may open (see [transferAndroidDownload]'s maxConnections). */
internal const val DOWNLOAD_SEGMENT_CONNECTIONS = 4

/** Smallest range worth its own connection; smaller files download over one connection. */
internal const val MIN_DOWNLOAD_SEGMENT_BYTES = 16L * 1024L * 1024L

private const val SEGMENT_ALIGNMENT_BYTES = 1024L * 1024L

/**
 * The segment layout on disk no longer matches what the server serves (it ignored a range, the
 * file changed, or the size differs): the partial data cannot be trusted.
 */
private class SegmentLayoutInvalidException(message: String) : IOException(message)

/** One byte range of a segmented download: `[start, end)` of the file, written to [file]. */
private class DownloadSegment(val file: File, val start: Long, val end: Long) {
    val length: Long get() = end - start
    val isComplete: Boolean get() = file.length() >= length
}

/**
 * Downloads [item] into `<fileName>.part`, resuming an existing partial with a Range request.
 *
 * [expectedTotalBytes] is the size the partial was written against. When a resumed (206)
 * response reports a different total, the partial belongs to a different file — e.g. a freshly
 * resolved link that serves another encode — so the download restarts from zero instead of
 * appending bytes across files. Unknown sizes on either side skip the check.
 *
 * With [maxConnections] > 1, a large file whose server honors ranges is split into up to that
 * many ranges fetched in parallel (debrid CDNs often cap speed per connection, not per user).
 * Segment 0 is the `.part` file itself; each further segment lives in `<fileName>.part.<start>`,
 * so the layout survives restarts without extra state, and is appended to `.part` in order once
 * every range is complete. A parallel connection the server refuses (429/503, or 403 from hosts
 * that cap connections that way) leaves its range for later, fetched one at a time once the
 * others finish; only then does an error status count as the link's own. A layout the server no longer matches (range
 * ignored, different size) is dropped and the download restarts from zero over one connection.
 */
internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    expectedTotalBytes: Long? = null,
    maxConnections: Int = 1,
    minSegmentBytes: Long = MIN_DOWNLOAD_SEGMENT_BYTES,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    val transfer = async(Dispatchers.IO) {
        try {
            require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
            val partial = File(directory, "${item.fileName}.part")
            var segmentationAllowed = maxConnections > 1
            val existingSegments = downloadSegmentStarts(directory, item.fileName)
            if (existingSegments.isNotEmpty()) {
                val total = expectedTotalBytes
                if (segmentationAllowed && total != null && total > 0L && existingSegments.last() < total) {
                    try {
                        onHeaders(total, validator)
                        return@async downloadSegments(
                            item, directory, validator, client, total, listOf(0L) + existingSegments,
                            activeCalls, onProgress,
                        )
                    } catch (invalid: SegmentLayoutInvalidException) {
                        segmentationAllowed = false
                    }
                }
                // A layout that cannot be resumed is worthless: start over in one piece.
                deleteDownloadPartialFiles(directory, item.fileName)
            }
            var offset = partial.takeIf(File::isFile)?.length() ?: 0L
            var restarted = false

            while (true) {
                ensureActive()
                val request = Request.Builder().url(item.sourceUrl).apply {
                    item.sourceHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                            !key.equals("Accept-Encoding", true)) header(key, value)
                    }
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        validator?.let { header("If-Range", it) }
                    }
                }.build()
                val call = client.newCall(request)
                activeCalls += call
                ensureActive()
                var segmentStarts: List<Long> = emptyList()
                var segmentValidator: String? = null
                var segmentTotal = 0L
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L && !restarted) {
                        offset = 0L
                        restarted = true
                        return@use
                    }
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && (range == null || range.first != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    if (resumed && !restarted && isResumeSizeMismatch(expectedTotalBytes, range?.second)) {
                        offset = 0L
                        restarted = true
                        return@use
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    val totalBytes = range?.second ?: contentLength?.let { startingBytes + it }
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    onHeaders(totalBytes, responseValidator)
                    if (segmentationAllowed && totalBytes != null && (resumed || response.acceptsByteRanges())) {
                        segmentStarts = planDownloadSegments(startingBytes, totalBytes, maxConnections, minSegmentBytes)
                        if (segmentStarts.isNotEmpty()) {
                            // Every range, the first included, is fetched by its own bounded
                            // request below; this open-ended one is no longer needed.
                            if (startingBytes == 0L) FileOutputStream(partial).close()
                            segmentValidator = responseValidator
                            segmentTotal = totalBytes
                            return@use
                        }
                    }
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                output.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            output.fd.sync()
                        }
                    }
                    ensureActive()
                    if (totalBytes != null && downloaded != totalBytes) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    return@async partial
                }
                activeCalls -= call
                if (segmentStarts.isNotEmpty()) {
                    segmentStarts.forEach { start -> FileOutputStream(segmentFile(directory, item.fileName, start)).close() }
                    try {
                        return@async downloadSegments(
                            item, directory, segmentValidator, client, segmentTotal, listOf(0L) + segmentStarts,
                            activeCalls, onProgress,
                        )
                    } catch (invalid: SegmentLayoutInvalidException) {
                        deleteDownloadPartialFiles(directory, item.fileName)
                        segmentationAllowed = false
                        offset = 0L
                        restarted = true
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            partial
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCalls.forEach(Call::cancel)
    }
}

/**
 * Downloads every incomplete range of the layout given by [starts] (ascending, first is 0)
 * in parallel, then appends the extra segment files to `.part` in order. Returns `.part`.
 */
private suspend fun downloadSegments(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient,
    totalBytes: Long,
    starts: List<Long>,
    activeCalls: MutableSet<Call>,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val partial = File(directory, "${item.fileName}.part")
    val segments = starts.mapIndexed { index, start ->
        DownloadSegment(
            file = if (index == 0) partial else segmentFile(directory, item.fileName, start),
            start = start,
            end = starts.getOrNull(index + 1) ?: totalBytes,
        )
    }
    if (segments.any { it.length <= 0L }) throw SegmentLayoutInvalidException("Invalid download segment layout")
    // A file longer than its range only happens when a merge was interrupted after appending a
    // segment to `.part` but before deleting it; its first bytes are still exactly its range.
    segments.forEach { segment ->
        if (!segment.file.isFile) FileOutputStream(segment.file).close()
        if (segment.file.length() > segment.length) {
            RandomAccessFile(segment.file, "rw").use { it.setLength(segment.length) }
        }
    }
    val downloaded = AtomicLong(segments.sumOf { it.file.length() })
    val progressLock = Any()
    val report = { synchronized(progressLock) { onProgress(downloaded.get(), totalBytes) } }
    report()
    val pending = segments.filterNot { it.isComplete }
    val deferred = try {
        pending.map { segment ->
            async(Dispatchers.IO) {
                segment.takeIf {
                    !downloadSegmentRange(
                        item, segment, validator, client, totalBytes, activeCalls, downloaded, report,
                        deferredStatusCodes = PARALLEL_REFUSAL_STATUS_CODES,
                    )
                }
            }
        }.awaitAll().filterNotNull()
    } catch (error: Throwable) {
        // Siblings blocked in a socket read only notice cancellation once their call closes.
        activeCalls.forEach(Call::cancel)
        throw error
    }
    // Ranges the server refused a parallel connection for: one at a time, now that it is free.
    deferred.forEach { segment ->
        downloadSegmentRange(
            item, segment, validator, client, totalBytes, activeCalls, downloaded, report,
            deferredStatusCodes = emptySet(),
        )
    }
    ensureActive()
    FileOutputStream(partial, true).use { output ->
        segments.drop(1).forEach { segment ->
            segment.file.inputStream().use { it.copyTo(output, 1024 * 1024) }
            output.fd.sync()
            // Deleted one by one: at most one segment is ever stored twice.
            segment.file.delete()
        }
    }
    if (partial.length() != totalBytes) throw IOException("Download ended before all bytes were received")
    partial
}

/** Statuses that, on an extra parallel connection, mean "too many connections" rather than a dead link. */
private val PARALLEL_REFUSAL_STATUS_CODES = setOf(403, 429, 503)

/**
 * Fetches the rest of [segment] with a bounded range request. Returns false when the server
 * answered with one of [deferredStatusCodes], so the range can wait for a free connection;
 * any other error status throws [DownloadHttpException].
 */
private fun CoroutineScope.downloadSegmentRange(
    item: DownloadItem,
    segment: DownloadSegment,
    validator: String?,
    client: OkHttpClient,
    totalBytes: Long,
    activeCalls: MutableSet<Call>,
    downloaded: AtomicLong,
    report: () -> Unit,
    deferredStatusCodes: Set<Int>,
): Boolean {
    var position = segment.start + segment.file.length()
    if (position >= segment.end) return true
    val request = Request.Builder().url(item.sourceUrl).apply {
        item.sourceHeaders.forEach { (key, value) ->
            if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                !key.equals("Accept-Encoding", true)) header(key, value)
        }
        header("Accept-Encoding", "identity")
        header("Range", "bytes=$position-${segment.end - 1}")
        validator?.let { header("If-Range", it) }
    }.build()
    val call = client.newCall(request)
    activeCalls += call
    try {
        ensureActive()
        call.execute().use { response ->
            if (response.code in deferredStatusCodes) return false
            if (response.code == 200 || response.code == 416) {
                throw SegmentLayoutInvalidException("Server no longer honors the download ranges")
            }
            if (!response.isSuccessful) throw DownloadHttpException(response.code)
            val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
            if (response.code != 206 || range == null || range.first != position) {
                throw SegmentLayoutInvalidException("Server returned an invalid download range")
            }
            if (range.second != null && range.second != totalBytes) {
                throw SegmentLayoutInvalidException("Download size changed")
            }
            val body = response.body ?: throw IOException("Download response is empty")
            body.byteStream().use { input ->
                FileOutputStream(segment.file, true).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (position < segment.end) {
                        ensureActive()
                        val wanted = minOf(buffer.size.toLong(), segment.end - position).toInt()
                        val size = input.read(buffer, 0, wanted)
                        if (size == -1) break
                        output.write(buffer, 0, size)
                        position += size
                        downloaded.addAndGet(size.toLong())
                        report()
                    }
                    output.fd.sync()
                }
            }
            ensureActive()
            if (position < segment.end) throw IOException("Download ended before all bytes were received")
            return true
        }
    } finally {
        activeCalls -= call
    }
}

private fun Response.acceptsByteRanges(): Boolean =
    header("Accept-Ranges")?.trim()?.equals("bytes", ignoreCase = true) == true

/**
 * Where a download of [totalBytes] already holding [startBytes] contiguous bytes is split:
 * the start offsets of segments 1..n-1 (segment 0 continues the partial from [startBytes]).
 * Empty when the remainder is too small to be worth more than one connection.
 */
internal fun planDownloadSegments(
    startBytes: Long,
    totalBytes: Long,
    connections: Int,
    minSegmentBytes: Long = MIN_DOWNLOAD_SEGMENT_BYTES,
): List<Long> {
    val remaining = totalBytes - startBytes
    if (connections < 2 || startBytes < 0L || remaining < 2 * minSegmentBytes) return emptyList()
    val count = minOf(connections.toLong(), remaining / minSegmentBytes).toInt()
    val size = remaining / count
    val alignment = minOf(SEGMENT_ALIGNMENT_BYTES, minSegmentBytes)
    return (1 until count)
        .map { index -> (startBytes + size * index) / alignment * alignment }
        .filter { it > startBytes && it < totalBytes }
        .distinct()
}

private fun segmentFile(directory: File, fileName: String, start: Long) = File(directory, "$fileName.part.$start")

/** Start offsets of the extra segment files of [fileName]'s partial download, ascending. */
internal fun downloadSegmentStarts(directory: File, fileName: String): List<Long> {
    val prefix = "$fileName.part."
    return directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(prefix) }
        .mapNotNull { it.name.removePrefix(prefix).toLongOrNull()?.takeIf { start -> start > 0L } }
        .sorted()
}

/** Bytes of [fileName]'s partial download on disk, across `.part` and any segment files. */
internal fun downloadPartialBytes(directory: File, fileName: String): Long =
    File(directory, "$fileName.part").length() +
        downloadSegmentStarts(directory, fileName).sumOf { segmentFile(directory, fileName, it).length() }

/** Deletes [fileName]'s partial download: `.part` and any segment files. True when none remain. */
internal fun deleteDownloadPartialFiles(directory: File, fileName: String): Boolean {
    val segments = downloadSegmentStarts(directory, fileName).map { segmentFile(directory, fileName, it) }
    return (segments + File(directory, "$fileName.part")).map { !it.exists() || it.delete() }.all { it }
}

/** True when a resumed response's total size proves the partial was written for a different file. */
internal fun isResumeSizeMismatch(expectedTotalBytes: Long?, resumedTotalBytes: Long?): Boolean =
    expectedTotalBytes != null && expectedTotalBytes > 0L &&
        resumedTotalBytes != null && resumedTotalBytes != expectedTotalBytes

internal fun parseDownloadContentRange(value: String): Pair<Long, Long?>? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || (total != null && end >= total)) return null
    return start to total
}
