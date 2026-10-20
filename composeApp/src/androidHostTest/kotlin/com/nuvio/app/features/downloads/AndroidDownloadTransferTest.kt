package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDownloadTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun resumesPartialFileWithRangeAndValidator(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"version-1\"").setBody(" world"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")

            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("hello world", output.readText())
            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range"))
            assertEquals("\"version-1\"", request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("Bearer test", request.getHeader("Authorization"))
        }
    }

    @Test
    fun ignoredRangeRestartsInsteadOfAppending(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new file"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("new file", output.readText())
        }
    }

    @Test
    fun unsatisfiedRangeRetriesFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("replacement"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("replacement", output.readText())
            assertEquals("bytes=10-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun mismatchedRangeDoesNotCorruptThePartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("bad"))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("hello") }
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("hello", partial.readText())
        }
    }

    @Test
    fun swappedLinkWithTheSameSizeResumesThePartial(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setBody(" world"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")

            // A freshly resolved link carries no validator; the size it reports
            // must match the one the partial was written for.
            val output = transferAndroidDownload(downloadItem(server.url("/fresh").toString()), directory,
                null, expectedTotalBytes = 11L, onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("hello world", output.readText())
            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range"))
            assertNull(request.getHeader("If-Range"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun swappedLinkWithADifferentSizeRestartsFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            // The fresh link serves another encode (13 bytes, not 11): appending
            // to the old partial would splice two files together.
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-12/13")
                .setBody(" other!!"))
            server.enqueue(MockResponse().setBody("other encode!"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")
            val totals = mutableListOf<Long?>()

            val output = transferAndroidDownload(downloadItem(server.url("/fresh").toString()), directory,
                null, expectedTotalBytes = 11L, onHeaders = { total, _ -> totals += total }, onProgress = { _, _ -> })

            assertEquals("other encode!", output.readText())
            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals(listOf<Long?>(13L), totals)
        }
    }

    @Test
    fun resumeSizeCheckSkipsUnknownSizes() {
        assertFalse(isResumeSizeMismatch(expectedTotalBytes = null, resumedTotalBytes = 13L))
        assertFalse(isResumeSizeMismatch(expectedTotalBytes = 11L, resumedTotalBytes = null))
        assertFalse(isResumeSizeMismatch(expectedTotalBytes = 0L, resumedTotalBytes = 13L))
        assertFalse(isResumeSizeMismatch(expectedTotalBytes = 11L, resumedTotalBytes = 11L))
        assertTrue(isResumeSizeMismatch(expectedTotalBytes = 11L, resumedTotalBytes = 13L))
    }

    @Test
    fun truncatedResponseNeverBecomesCompletedFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val directory = temporary.newFolder()
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertFalse(File(directory, "video.mkv").exists())
            assertTrue(File(directory, "video.mkv.part").length() < 10)
        }
    }

    @Test
    fun cancellationClosesBlockedSocketPromptlyAndKeepsPartialBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").throttleBody(1, 10, TimeUnit.SECONDS))
            val directory = temporary.newFolder()
            val task = async(Dispatchers.Default) {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            withTimeout(5_000) {
                while (File(directory, "video.mkv.part").length() == 0L) delay(10)
            }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals(1L, File(directory, "video.mkv.part").length())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }

    @Test
    fun streamRefreshBudgetBurnsOutWhenDeathsMakeNoProgress() {
        val budget = StreamRefreshBudget(maxConsecutiveRefreshes = 2)
        assertTrue(budget.tryConsumeRefresh(100L))
        // Same byte position again: no progress since the refresh, budget drains.
        assertTrue(budget.tryConsumeRefresh(100L))
        assertFalse(budget.tryConsumeRefresh(100L))
        assertEquals(2, budget.consecutiveRefreshes)
    }

    @Test
    fun streamRefreshBudgetResetsAfterProgressPastTheRefreshMark() {
        val budget = StreamRefreshBudget(maxConsecutiveRefreshes = 2)
        assertTrue(budget.tryConsumeRefresh(100L))
        assertTrue(budget.tryConsumeRefresh(100L))
        // The next attempt transferred beyond the mark set at the last refresh
        // before dying: the expiries are spaced out, so the budget starts over
        // instead of killing a download that is making real progress.
        assertTrue(budget.tryConsumeRefresh(250L))
        assertEquals(1, budget.consecutiveRefreshes)
        assertTrue(budget.tryConsumeRefresh(250L))
        assertFalse(budget.tryConsumeRefresh(250L))
    }

    @Test
    fun streamRefreshBudgetCountsADeathAtTheResumeOffsetAsProgressOnlyOnce() {
        // A resumed download's first stale death sees the whole .part as
        // "progress" past the initial zero mark; after that the mark is the
        // resume offset, so repeated deaths there still burn out.
        val budget = StreamRefreshBudget(maxConsecutiveRefreshes = 2)
        assertTrue(budget.tryConsumeRefresh(500L))
        assertTrue(budget.tryConsumeRefresh(500L))
        assertFalse(budget.tryConsumeRefresh(500L))
    }

    @Test
    fun retryPolicyDistinguishesTransientAndPermanentFailures() {
        assertTrue(shouldRetryAndroidDownload(IOException("connection lost"), 0, isTorrent = false))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(503), 0, isTorrent = false))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(429), 0, isTorrent = false))
        // A dead HTTP link stays non-retryable: retrying would hit the same URL.
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(403), 0, isTorrent = false))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(404), 0, isTorrent = false))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(410), 0, isTorrent = false))
        assertFalse(shouldRetryAndroidDownload(IOException("connection lost"), 4, isTorrent = false))
    }

    @Test
    fun retryPolicyRetriesStaleLinksForTorrentsWithinBudget() {
        // A torrent retry re-enters execute() and prepares a fresh loopback
        // stream, so an expired link is transient there.
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(403), 0, isTorrent = true))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(404), 0, isTorrent = true))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(410), 3, isTorrent = true))
        // The shared retry budget still caps torrent stale-link retries.
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(403), 4, isTorrent = true))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(410), 4, isTorrent = true))
        // Non-stale permanent HTTP failures stay non-retryable for torrents too.
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(401), 0, isTorrent = true))
    }
}

internal fun downloadItem(url: String = "https://example.com/video.mkv", id: String = "test-download") = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "test-movie",
    parentMetaType = "movie",
    videoId = "test-movie",
    title = "Download test",
    streamTitle = "Test video",
    providerName = "Test",
    sourceUrl = url,
    sourceHeaders = mapOf("Authorization" to "Bearer test"),
    fileName = "video.mkv",
    status = DownloadStatus.Downloading,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
