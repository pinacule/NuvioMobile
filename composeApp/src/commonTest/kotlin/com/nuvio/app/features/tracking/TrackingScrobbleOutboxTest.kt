package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The outbox itself persists through platform storage, so these tests drive
 * [coalesceQueuedScrobbles] — the exact list transformation `enqueue`
 * applies under its lock — with the records `enqueue` would have built.
 */
class TrackingScrobbleOutboxTest {
    @Test
    fun `pause for one episode does not destroy queued pause for another episode of the same show`() {
        val pauseE3 = queued(action = "pause", season = 1, episode = 3, progress = 40.0, queuedAt = 1_000L)
        val pauseE4 = queued(action = "pause", season = 1, episode = 4, progress = 55.0, queuedAt = 2_000L)

        val records = coalesceQueuedScrobbles(coalesceQueuedScrobbles(emptyList(), pauseE3), pauseE4)

        assertEquals(listOf(pauseE3, pauseE4), records)
    }

    @Test
    fun `stop for an episode is not replaced by a later pause of a different episode`() {
        val stopE3 = queued(action = "stop", season = 1, episode = 3, progress = 92.0, queuedAt = 1_000L)
        val pauseE4 = queued(action = "pause", season = 1, episode = 4, progress = 30.0, queuedAt = 2_000L)

        val records = coalesceQueuedScrobbles(coalesceQueuedScrobbles(emptyList(), stopE3), pauseE4)

        assertEquals(listOf(stopE3, pauseE4), records)
    }

    @Test
    fun `stop for the same episode replaces its earlier pause`() {
        val pauseE3 = queued(action = "pause", season = 1, episode = 3, progress = 40.0, queuedAt = 1_000L)
        val stopE3 = queued(action = "stop", season = 1, episode = 3, progress = 95.0, queuedAt = 2_000L)

        val records = coalesceQueuedScrobbles(coalesceQueuedScrobbles(emptyList(), pauseE3), stopE3)

        assertEquals(listOf(stopE3), records)
    }

    @Test
    fun `same episode number in a different season does not coalesce`() {
        val pauseS1E3 = queued(action = "pause", season = 1, episode = 3, progress = 40.0, queuedAt = 1_000L)
        val pauseS2E3 = queued(action = "pause", season = 2, episode = 3, progress = 40.0, queuedAt = 2_000L)

        val records = coalesceQueuedScrobbles(coalesceQueuedScrobbles(emptyList(), pauseS1E3), pauseS2E3)

        assertEquals(listOf(pauseS1E3, pauseS2E3), records)
    }

    @Test
    fun `movie scrobbles still coalesce with each other but not with episodes`() {
        val moviePause = queued(action = "pause", season = null, episode = null, progress = 20.0, queuedAt = 1_000L)
        val movieStop = queued(action = "stop", season = null, episode = null, progress = 90.0, queuedAt = 2_000L)

        // A reference without an episode (same show stableKey) must not
        // coalesce with an episode of that show either.
        val wholeShow = QueuedScrobble(
            providerId = TrackingProviderId.SIMKL.storageId,
            profileId = 1,
            action = "pause",
            media = TrackingMediaReference(
                kind = TrackingMediaKind.SHOW,
                title = "Example",
                ids = TrackingExternalIds(simkl = 12345L),
            ),
            progressPercent = 20.0,
            queuedAtEpochMs = 3_000L,
        )
        assertNotEquals(wholeShow.coalesceKey, queued(action = "pause", season = 1, episode = 1, progress = 20.0, queuedAt = 3_000L).coalesceKey)
        val records = coalesceQueuedScrobbles(coalesceQueuedScrobbles(emptyList(), moviePause), movieStop)

        assertEquals(listOf(movieStop), records)
    }

    @Test
    fun `pause above the watched threshold replays as a pause instead of flushing to history`() {
        val pauseAt85 = queued(action = "pause", season = 1, episode = 3, progress = 85.0, queuedAt = 1_000L)

        val (history, replay) = splitQueuedScrobblesForFlush(listOf(pauseAt85))

        assertEquals(emptyList(), history)
        assertEquals(listOf(pauseAt85), replay)
    }

    @Test
    fun `stop above the watched threshold flushes to history`() {
        val stopAt85 = queued(action = "stop", season = 1, episode = 3, progress = 85.0, queuedAt = 1_000L)
        val stopAt50 = queued(action = "stop", season = 1, episode = 4, progress = 50.0, queuedAt = 2_000L)
        val startAt95 = queued(action = "start", season = 1, episode = 5, progress = 95.0, queuedAt = 3_000L)

        val (history, replay) = splitQueuedScrobblesForFlush(listOf(stopAt85, stopAt50, startAt95))

        assertEquals(listOf(stopAt85), history)
        assertEquals(listOf(stopAt50, startAt95), replay)
    }

    @Test
    fun `history flush decision removes only fully delivered batches`() {
        assertEquals(
            HistoryFlushDecision.REMOVE,
            historyFlushDecision(TrackingMutationResult(attemptedCount = 3, notFoundCount = 0)),
        )
        assertEquals(
            HistoryFlushDecision.KEEP_UNATTEMPTED,
            historyFlushDecision(TrackingMutationResult(attemptedCount = 0)),
        )
        assertEquals(
            HistoryFlushDecision.KEEP_PARTIAL,
            historyFlushDecision(TrackingMutationResult(attemptedCount = 3, notFoundCount = 1)),
        )
    }

    @Test
    fun `failed flush attempts increment only the failed records`() {
        val failed = queued(action = "pause", season = 1, episode = 3, progress = 40.0, queuedAt = 1_000L)
        val untouched = queued(action = "pause", season = 1, episode = 4, progress = 40.0, queuedAt = 2_000L)

        val application = applyFlushFailures(
            records = listOf(failed, untouched),
            failedIdentities = setOf(failed.identity),
            maxAttempts = 5,
        )

        assertEquals(emptyList(), application.dropped)
        assertEquals(
            listOf(failed.copy(attemptCount = 1), untouched),
            application.records,
        )
    }

    @Test
    fun `record is dropped when its failed attempts reach the cap`() {
        val almostOut = queued(action = "stop", season = 1, episode = 3, progress = 90.0, queuedAt = 1_000L)
            .copy(attemptCount = 4)

        val application = applyFlushFailures(
            records = listOf(almostOut),
            failedIdentities = setOf(almostOut.identity),
            maxAttempts = 5,
        )

        assertEquals(emptyList(), application.records)
        assertEquals(listOf(almostOut.copy(attemptCount = 5)), application.dropped)
    }

    private fun queued(
        action: String,
        season: Int?,
        episode: Int?,
        progress: Double,
        queuedAt: Long,
    ): QueuedScrobble = QueuedScrobble(
        providerId = TrackingProviderId.SIMKL.storageId,
        profileId = 1,
        action = action,
        media = TrackingMediaReference(
            kind = if (episode == null) TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW,
            title = "Example",
            ids = TrackingExternalIds(simkl = 12345L),
            episode = episode?.let { number -> TrackingEpisode(season = season, number = number) },
        ),
        progressPercent = progress,
        queuedAtEpochMs = queuedAt,
    )
}
