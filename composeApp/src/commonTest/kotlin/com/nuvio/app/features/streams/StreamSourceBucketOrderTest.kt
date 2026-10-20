package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Render-bucket order for multi-source groups (audit R-7): StreamsScreen
 * re-buckets each group by source name at render time. With the
 * English-audio preference off the buckets are exactly the legacy
 * alphabetical order; with it on, buckets containing an English-audio
 * stream render first (alphabetical within each class), so the
 * repository-level dub-first order is not capped at bucket granularity.
 * The helper returns bucket names only — streams inside a bucket keep
 * the repository order by construction.
 */
class StreamSourceBucketOrderTest {

    private fun stream(name: String) = StreamItem(
        name = name,
        addonName = "Addon",
        addonId = "addon:test",
    )

    private fun englishDub(name: String) = stream("$name [English Dub]")

    private fun bucketsOf(vararg buckets: Pair<String, List<StreamItem>>) = mapOf(*buckets)

    @Test
    fun prefOff_bucketsStayAlphabetical_evenWhenEnglishSitsInALaterBucket() {
        val buckets = bucketsOf(
            "Zebra" to listOf(englishDub("Show S01E01 1080p")),
            "Alpha" to listOf(stream("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Alpha", "Zebra"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = false),
        )
    }

    @Test
    fun prefOff_bucketOrderIsCaseInsensitiveAlphabetical() {
        val buckets = bucketsOf(
            "beta" to listOf(stream("Show S01E01 1080p")),
            "Alpha" to listOf(stream("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Alpha", "beta"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = false),
        )
    }

    @Test
    fun prefOn_englishContainingBucketRendersFirst() {
        // The R-7 headline case: Alpha carries only the JP release, Zebra
        // carries the English dub — alphabetical order would bury the dub.
        val buckets = bucketsOf(
            "Alpha" to listOf(stream("Show S01E01 1080p")),
            "Zebra" to listOf(englishDub("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Zebra", "Alpha"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = true),
        )
    }

    @Test
    fun prefOn_alphabeticalOrderWithinEachClass() {
        val buckets = bucketsOf(
            "Beta" to listOf(englishDub("Show S01E01 1080p")),
            "alpha" to listOf(englishDub("Show S01E01 1080p")),
            "Zulu" to listOf(stream("Show S01E01 1080p")),
            "Yankee" to listOf(stream("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("alpha", "Beta", "Yankee", "Zulu"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = true),
        )
    }

    @Test
    fun prefOn_bucketWithAnyEnglishStreamCountsAsEnglishBucket() {
        val buckets = bucketsOf(
            "Zeta" to listOf(stream("Show S01E01 1080p"), englishDub("Show S01E01 720p")),
            "Alpha" to listOf(stream("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Zeta", "Alpha"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = true),
        )
    }

    @Test
    fun prefOn_noEnglishStreams_pureAlphabetical() {
        val buckets = bucketsOf(
            "Zebra" to listOf(stream("Show S01E01 1080p")),
            "Alpha" to listOf(stream("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Alpha", "Zebra"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = true),
        )
    }

    @Test
    fun prefOn_allEnglishBuckets_pureAlphabetical() {
        val buckets = bucketsOf(
            "Zebra" to listOf(englishDub("Show S01E01 1080p")),
            "Alpha" to listOf(englishDub("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Alpha", "Zebra"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = true),
        )
    }

    @Test
    fun prefOn_singleBucket_unchanged() {
        val buckets = bucketsOf(
            "Only" to listOf(stream("Show S01E01 1080p")),
        )

        assertEquals(
            listOf("Only"),
            buckets.sortedSourceBucketNames(preferEnglishAudio = true),
        )
    }
}
