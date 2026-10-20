package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SLOT_POLL_MS = 250L

/**
 * A concurrency cap for one kind of download whose slots are handed out in series-episode order
 * (E1 before E2, ...). Workers start in whatever order WorkManager/JobScheduler runs them, so a
 * bare semaphore would let a later episode grab a free slot first; the ordered queue guarantees
 * first-episode-first no matter the arrival order.
 *
 * Entries are (item, token): the token identifies one acquire attempt — acquisition matches the
 * queue head by token identity, and a cancelled attempt removes exactly its own entry. The queue
 * is in-memory only; across a process restart workers re-insert themselves in episode order as
 * they arrive. Every successful [acquire] must be paired with one [release].
 */
internal class OrderedDownloadSlots(maxConcurrent: Int) {
    val permits = Semaphore(maxConcurrent)
    private val queueMutex = Mutex()
    private val queue = ArrayDeque<Pair<DownloadItem, Any>>()

    suspend fun acquire(item: DownloadItem, token: Any) {
        queueMutex.withLock {
            val index = queue.indexOfFirst { (queued, _) ->
                downloadSeriesEpisodeComparator.compare(item, queued) < 0
            }
            if (index < 0) queue.addLast(item to token) else queue.add(index, item to token)
        }
        try {
            while (true) {
                val acquired = queueMutex.withLock { tryTakeOrderedSlotQueueHead(queue, token, permits) }
                if (acquired) return
                delay(SLOT_POLL_MS)
            }
        } catch (cancellation: CancellationException) {
            // NonCancellable: this coroutine is already being torn down, so a plain withLock here
            // could itself throw CancellationException under contention and leak the queue entry.
            withContext(NonCancellable) {
                queueMutex.withLock {
                    queue.removeAll { (_, queuedToken) -> queuedToken === token }
                }
            }
            throw cancellation
        }
    }

    fun release() = permits.release()
}

/**
 * Runs [block] while holding one of these slots, waiting in episode order for it first.
 * A cancelled wait never held a permit, so it releases nothing.
 */
internal suspend inline fun <T> OrderedDownloadSlots.withSlot(item: DownloadItem, block: () -> T): T {
    acquire(item, Any())
    try {
        return block()
    } finally {
        release()
    }
}

/**
 * Takes a download slot for the attempt identified by [token] when — and only when — that
 * attempt's own entry heads [queue] and a slot permit is free; removes exactly that entry.
 * Matching by token identity rather than item id matters: the preload path can queue two
 * attempts for the same item id (pause cancels one attempt, resume starts another), and an
 * attempt that consumed the *other* attempt's head entry would leave its own entry behind as
 * a permanent phantom head blocking every later download of that kind until process restart.
 * Callers must hold the queue mutex.
 */
internal fun tryTakeOrderedSlotQueueHead(
    queue: ArrayDeque<Pair<DownloadItem, Any>>,
    token: Any,
    slots: Semaphore,
): Boolean {
    if (queue.firstOrNull()?.second !== token) return false
    if (!slots.tryAcquire()) return false
    queue.removeFirst()
    return true
}
