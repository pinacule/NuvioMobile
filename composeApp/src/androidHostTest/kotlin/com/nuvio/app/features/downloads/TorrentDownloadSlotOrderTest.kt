package com.nuvio.app.features.downloads

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Torrent download slots must be handed out first-episode-first no matter in which order the
 * transfer workers start (WorkManager/JobScheduler make no ordering guarantees).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TorrentDownloadSlotOrderTest {

    private fun episodeItem(id: String, episode: Int): DownloadItem =
        downloadItem(id = id).copy(
            parentMetaId = "show",
            videoId = "show:1:$episode",
            seasonNumber = 1,
            episodeNumber = episode,
        )

    @Test
    fun slotsAreHandedOutInEpisodeOrderRegardlessOfArrival() = runBlocking {
        val acquired = mutableListOf<String>()

        suspend fun holdSlot(item: DownloadItem) {
            val token = Any()
            var held = false
            try {
                acquireOrderedTorrentSlot(item, token)
                held = true
                acquired.add(item.id)
                awaitCancellation()
            } finally {
                if (held) torrentDownloadSlots.release()
            }
        }

        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            // E3 arrives first and takes a slot; E1 arrives second and takes the other.
            jobs += async { holdSlot(episodeItem("e3", 3)) }
            jobs += async { holdSlot(episodeItem("e1", 1)) }
            withTimeout(10_000) { while (acquired.size < 2) delay(50) }

            // E2 and E4 arrive while both slots are busy: neither may jump ahead.
            jobs += async { holdSlot(episodeItem("e2", 2)) }
            jobs += async { holdSlot(episodeItem("e4", 4)) }
            delay(750)
            assertFalse(acquired.contains("e2"), "E2 jumped the queue: $acquired")
            assertFalse(acquired.contains("e4"), "E4 jumped the queue: $acquired")

            // Freeing E3's slot must hand it to E2 (episode order), not E4.
            jobs[0].cancel()
            withTimeout(10_000) { while (!acquired.contains("e2")) delay(50) }
            assertFalse(acquired.contains("e4"), "E4 jumped ahead of E2: $acquired")

            // Freeing E1's slot hands it to E4.
            jobs[1].cancel()
            withTimeout(10_000) { while (!acquired.contains("e4")) delay(50) }

            assertEquals(listOf("e3", "e1", "e2", "e4"), acquired)
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}
