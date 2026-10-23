package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchProgressResumePositionTest {
    private fun entry(
        source: String,
        lastPositionMs: Long,
        durationMs: Long,
        progressPercent: Float?,
    ) = WatchProgressEntry(
        contentType = "movie",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        videoId = "tt1",
        title = "Movie",
        lastPositionMs = lastPositionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = 1L,
        progressPercent = progressPercent,
        source = source,
    )

    @Test
    fun `provider entry resumes at percent of the real duration, not the rounded-runtime position`() {
        // Simkl stored 50% of a 90-minute (rounded) runtime = 45:00. The real
        // file runs 93 minutes, so the resume point is 46:30.
        val simklEntry = entry(
            source = WatchProgressSourceSimklPlayback,
            lastPositionMs = 2_700_000L,
            durationMs = 5_400_000L,
            progressPercent = 50f,
        )

        assertEquals(2_790_000L, simklEntry.resolveResumePosition(actualDurationMs = 5_580_000L))
    }

    @Test
    fun `local entry keeps its recorded absolute position`() {
        val localEntry = entry(
            source = WatchProgressSourceLocal,
            lastPositionMs = 2_700_000L,
            durationMs = 5_400_000L,
            progressPercent = 50f,
        )

        assertEquals(2_700_000L, localEntry.resolveResumePosition(actualDurationMs = 5_580_000L))
    }

    @Test
    fun `provider entry without a percent falls back to the stored position`() {
        val simklEntry = entry(
            source = WatchProgressSourceSimklPlayback,
            lastPositionMs = 2_700_000L,
            durationMs = 5_400_000L,
            progressPercent = null,
        )

        assertEquals(2_700_000L, simklEntry.resolveResumePosition(actualDurationMs = 5_580_000L))
    }
}
