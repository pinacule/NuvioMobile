package com.nuvio.app.features.downloads

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /**
     * Regression (bug sweep 2026-10-20, H1): the preload path can queue two attempts for the
     * same item id — pause cancels one attempt, resume starts another with the same
     * DownloadItem.id. Acquisition must match the queue head by attempt token, never by item
     * id: an attempt that consumed the other attempt's head entry left its own entry behind
     * as a permanent phantom head, hanging every later torrent download at Queued 0%.
     */
    @Test
    fun queuedAttemptCannotTakeSlotForSameIdAttemptAheadOfIt() {
        val item = episodeItem("dup", 5)
        val firstToken = Any()
        val secondToken = Any()
        val queue = ArrayDeque<Pair<DownloadItem, Any>>()
        queue.addLast(item to firstToken)
        queue.addLast(item to secondToken)
        val slots = Semaphore(1)

        // The second attempt must not consume the first attempt's head entry, even though
        // the item id matches and a permit is free.
        assertFalse(tryTakeOrderedSlotQueueHead(queue, secondToken, slots))
        assertEquals(2, queue.size)
        assertTrue(slots.tryAcquire(), "second attempt consumed a slot permit")
        slots.release()

        // The head's owner takes the slot and removes exactly its own entry.
        assertTrue(tryTakeOrderedSlotQueueHead(queue, firstToken, slots))
        assertEquals(listOf(secondToken), queue.map { it.second })

        // Once the first attempt's run finishes and frees the permit, the surviving attempt
        // takes the slot and removes its own entry too — the queue drains fully, no phantom.
        slots.release()
        assertTrue(tryTakeOrderedSlotQueueHead(queue, secondToken, slots))
        assertTrue(queue.isEmpty(), "phantom queue entry left behind: $queue")
    }

    @Test
    fun headAttemptCannotTakeSlotWithoutFreePermit() {
        val item = episodeItem("dup", 5)
        val token = Any()
        val queue = ArrayDeque<Pair<DownloadItem, Any>>()
        queue.addLast(item to token)
        val slots = Semaphore(1)
        assertTrue(slots.tryAcquire()) // drain the only permit

        assertFalse(tryTakeOrderedSlotQueueHead(queue, token, slots))
        assertEquals(1, queue.size, "head entry was removed without acquiring a slot")

        slots.release()
        assertTrue(tryTakeOrderedSlotQueueHead(queue, token, slots))
        assertTrue(queue.isEmpty())
    }

    /**
     * End-to-end version of the H1 interleaving through [acquireOrderedTorrentSlot]: two
     * attempts for the same item queue behind busy slots, the first is cancelled (preload
     * pause), the second must then acquire with its own entry and leave nothing behind, so a
     * later episode's attempt still acquires promptly.
     */
    @Test
    fun duplicateIdAttemptsLeaveNoPhantomQueueHead() = runBlocking {
        val acquired = mutableListOf<String>()

        suspend fun holdSlot(item: DownloadItem, tag: String) {
            var held = false
            try {
                acquireOrderedTorrentSlot(item, Any())
                held = true
                acquired.add(tag)
                awaitCancellation()
            } finally {
                if (held) torrentDownloadSlots.release()
            }
        }

        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            // Both slots busy with earlier episodes.
            val holder1 = async { holdSlot(episodeItem("e1", 1), "h1") }
            jobs += holder1
            val holder2 = async { holdSlot(episodeItem("e2", 2), "h2") }
            jobs += holder2
            withTimeout(10_000) { while (acquired.size < 2) delay(50) }

            // Two attempts for the SAME item id queue behind the busy slots.
            val dupItem = episodeItem("dup", 5)
            val firstAttempt = async { holdSlot(dupItem, "a1") }
            jobs += firstAttempt
            val secondAttempt = async { holdSlot(dupItem, "a2") }
            jobs += secondAttempt
            delay(750)
            assertFalse(acquired.contains("a1"), "first dup attempt acquired early: $acquired")
            assertFalse(acquired.contains("a2"), "second dup attempt acquired early: $acquired")

            // Preload pause: the first attempt is cancelled; its entry must be removed.
            firstAttempt.cancel()
            firstAttempt.join()

            // Freeing a slot hands it to the surviving attempt — with its own entry.
            holder1.cancel()
            withTimeout(10_000) { while (!acquired.contains("a2")) delay(50) }
            assertFalse(acquired.contains("a1"), "cancelled attempt acquired: $acquired")

            // The surviving attempt finishes; freeing the other slot must let the next
            // episode's attempt acquire promptly (a phantom head would hang it forever).
            secondAttempt.cancel() // releases its permit via holdSlot's finally.
            holder2.cancel() // releases its permit.
            val thirdAttempt = async { holdSlot(episodeItem("e6", 6), "a3") }
            jobs += thirdAttempt
            withTimeout(10_000) { while (!acquired.contains("a3")) delay(50) }
        } finally {
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            // Restore any permit a failed assertion left held so other tests are unaffected.
            var drained = 0
            while (torrentDownloadSlots.tryAcquire()) drained++
            repeat(drained) { torrentDownloadSlots.release() }
        }
    }
}
