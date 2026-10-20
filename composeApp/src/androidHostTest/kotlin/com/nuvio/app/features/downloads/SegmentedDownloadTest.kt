package com.nuvio.app.features.downloads

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentedDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    private val content = Random(42).nextBytes(64 * 1024)
    private val segmentBytes = 8L * 1024L

    /** Serves [content] with byte-range support, like a debrid CDN. */
    private inner class RangeDispatcher(
        private val honorRanges: Boolean = true,
        /** Ranged requests beyond this many in flight get [refusalCode]. */
        private val maxConcurrent: Int = Int.MAX_VALUE,
        private val refusalCode: Int = 429,
    ) : Dispatcher() {
        val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        private val inFlight = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val rangeHeader = request.getHeader("Range")
            ranges += rangeHeader
            if (rangeHeader == null || !honorRanges) {
                return MockResponse().setHeader("Accept-Ranges", "bytes").setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(content))
            }
            if (inFlight.incrementAndGet() > maxConcurrent) {
                inFlight.decrementAndGet()
                return MockResponse().setResponseCode(refusalCode)
            }
            try {
                // A little latency so ranges genuinely overlap in time.
                Thread.sleep(20)
                val (startText, endText) = rangeHeader.removePrefix("bytes=").split("-")
                val start = startText.toInt()
                if (start >= content.size) return MockResponse().setResponseCode(416)
                val end = minOf(endText.takeIf { it.isNotBlank() }?.toInt() ?: Int.MAX_VALUE, content.size - 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${content.size}")
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(content.copyOfRange(start, end + 1)))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private suspend fun download(server: MockWebServer, directory: File, expectedTotal: Long? = null): File =
        transferAndroidDownload(
            item = downloadItem(server.url("/video").toString()),
            directory = directory,
            validator = null,
            expectedTotalBytes = expectedTotal,
            maxConnections = 4,
            minSegmentBytes = segmentBytes,
            onHeaders = { _, _ -> },
            onProgress = { _, _ -> },
        )

    @Test
    fun largeFileIsFetchedAsParallelRangesAndAssembledInOrder(): Unit = runBlocking {
        MockWebServer().use { server ->
            val dispatcher = RangeDispatcher()
            server.dispatcher = dispatcher
            val directory = temporary.newFolder()

            val output = download(server, directory)

            assertContentEquals(content, output.readBytes())
            // One probe, then four bounded ranges covering the whole file.
            val bounded = dispatcher.ranges.filterNotNull()
            assertEquals(4, bounded.size)
            assertTrue(bounded.all { Regex("""bytes=\d+-\d+""").matches(it) })
            assertTrue(downloadSegmentStarts(directory, "video.mkv").isEmpty())
        }
    }

    @Test
    fun interruptedLayoutResumesOnlyTheMissingBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            val dispatcher = RangeDispatcher()
            server.dispatcher = dispatcher
            val directory = temporary.newFolder()
            val starts = planDownloadSegments(0L, content.size.toLong(), 4, segmentBytes)
            assertEquals(3, starts.size)
            // Segment 0 (.part) and segment 1 half done; segment 2 complete; segment 3 empty.
            File(directory, "video.mkv.part").writeBytes(content.copyOfRange(0, 1000))
            File(directory, "video.mkv.part.${starts[0]}")
                .writeBytes(content.copyOfRange(starts[0].toInt(), starts[0].toInt() + 2000))
            File(directory, "video.mkv.part.${starts[1]}")
                .writeBytes(content.copyOfRange(starts[1].toInt(), starts[2].toInt()))
            File(directory, "video.mkv.part.${starts[2]}").writeBytes(ByteArray(0))
            assertEquals(1000L + 2000L + (starts[2] - starts[1]), downloadPartialBytes(directory, "video.mkv"))

            val output = download(server, directory, expectedTotal = content.size.toLong())

            assertContentEquals(content, output.readBytes())
            assertEquals(
                setOf(
                    "bytes=1000-${starts[0] - 1}",
                    "bytes=${starts[0] + 2000}-${starts[1] - 1}",
                    "bytes=${starts[2]}-${content.size - 1}",
                ),
                dispatcher.ranges.toSet(),
            )
        }
    }

    @Test
    fun refusedExtraConnectionsAreFetchedOnceOthersFinish(): Unit = runBlocking {
        MockWebServer().use { server ->
            // A host that allows one ranged connection at a time answers the rest with 429.
            server.dispatcher = RangeDispatcher(maxConcurrent = 1)
            val directory = temporary.newFolder()

            val output = download(server, directory)

            assertContentEquals(content, output.readBytes())
        }
    }

    @Test
    fun hostThatCapsConnectionsWith403IsNotMistakenForADeadLink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(maxConcurrent = 1, refusalCode = 403)
            val directory = temporary.newFolder()

            val output = download(server, directory)

            assertContentEquals(content, output.readBytes())
        }
    }

    @Test
    fun deadLinkStillFailsWithItsStatusOnceRangesRunOneAtATime(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(maxConcurrent = 0, refusalCode = 403)
            val directory = temporary.newFolder()

            val error = assertFailsWith<DownloadHttpException> { download(server, directory) }

            // The scheduler's stale-link refresh keys off this; the layout is kept for the fresh link.
            assertEquals(403, error.statusCode)
            assertEquals(3, downloadSegmentStarts(directory, "video.mkv").size)
        }
    }

    @Test
    fun serverThatIgnoresRangesFallsBackToOneConnection(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(honorRanges = false)
            val directory = temporary.newFolder()

            val output = download(server, directory)

            assertContentEquals(content, output.readBytes())
            assertTrue(downloadSegmentStarts(directory, "video.mkv").isEmpty())
        }
    }

    @Test
    fun layoutForADifferentSizedFileIsDroppedAndRestarted(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher()
            val directory = temporary.newFolder()
            // Segments planned for a 96 KiB file; the link now serves 64 KiB (another encode).
            File(directory, "video.mkv.part").writeBytes(ByteArray(100))
            File(directory, "video.mkv.part.${48 * 1024}").writeBytes(ByteArray(100))

            val output = download(server, directory, expectedTotal = 96L * 1024L)

            assertContentEquals(content, output.readBytes())
            assertTrue(downloadSegmentStarts(directory, "video.mkv").isEmpty())
        }
    }

    @Test
    fun mergeInterruptedAfterAnAppendIsRepaired(): Unit = runBlocking {
        MockWebServer().use { server ->
            val dispatcher = RangeDispatcher()
            server.dispatcher = dispatcher
            val directory = temporary.newFolder()
            val starts = planDownloadSegments(0L, content.size.toLong(), 4, segmentBytes)
            // Every range complete; the merge appended part of segment 1 and was killed.
            File(directory, "video.mkv.part").writeBytes(content.copyOfRange(0, starts[0].toInt() + 500))
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1)?.toInt() ?: content.size
                File(directory, "video.mkv.part.$start").writeBytes(content.copyOfRange(start.toInt(), end))
            }

            val output = download(server, directory, expectedTotal = content.size.toLong())

            assertContentEquals(content, output.readBytes())
            assertTrue(dispatcher.ranges.isEmpty())
        }
    }

    @Test
    fun segmentPlanSplitsOnlyWhatIsWorthAConnection() {
        val mib = 1024L * 1024L
        assertEquals(emptyList(), planDownloadSegments(0L, 20 * mib, 4))
        assertEquals(emptyList(), planDownloadSegments(0L, 1024 * mib, 1))
        assertEquals(listOf(256 * mib, 512 * mib, 768 * mib), planDownloadSegments(0L, 1024 * mib, 4))
        // A resumed partial: only the remainder is split, starting after what is on disk.
        val resumed = planDownloadSegments(400 * mib, 1024 * mib, 4)
        assertEquals(3, resumed.size)
        assertTrue(resumed.all { it > 400 * mib && it < 1024 * mib && it % mib == 0L })
        // Remainder fits only two minimum-size segments.
        assertEquals(1, planDownloadSegments(0L, 40 * mib, 4).size)
    }

    @Test
    fun partialHelpersCoverSegmentFiles() {
        val directory = temporary.newFolder()
        File(directory, "video.mkv.part").writeBytes(ByteArray(10))
        File(directory, "video.mkv.part.4096").writeBytes(ByteArray(5))
        File(directory, "video.mkv.part.notanumber").writeBytes(ByteArray(7))
        File(directory, "other.mkv.part.4096").writeBytes(ByteArray(7))
        assertEquals(listOf(4096L), downloadSegmentStarts(directory, "video.mkv"))
        assertEquals(15L, downloadPartialBytes(directory, "video.mkv"))
        assertTrue(deleteDownloadPartialFiles(directory, "video.mkv"))
        assertFalse(File(directory, "video.mkv.part").exists())
        assertFalse(File(directory, "video.mkv.part.4096").exists())
        assertTrue(File(directory, "other.mkv.part.4096").exists())
    }
}
