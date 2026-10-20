package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridStreamPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Display-order guarantees for the English-dub preference (audit M-S4/M-S5/M-S6):
 * with the preference on, the manual stream list is dub-first across scraper
 * sourceNames, across debrid/passthrough blocks, and on the embedded path;
 * with it off, ordering is exactly what it was before the fix.
 */
class StreamDubDisplaySortTest {

    private fun stream(
        name: String,
        sourceName: String? = null,
        seeders: Int? = null,
        url: String? = null,
    ) = StreamItem(
        name = name,
        description = seeders?.let { "\uD83D\uDC64 $it" },
        url = url,
        sourceName = sourceName,
        addonName = "Addon",
        addonId = "addon:test",
    )

    private fun englishDub(name: String, sourceName: String? = null, seeders: Int? = null, url: String? = null) =
        stream(name = "$name [English Dub]", sourceName = sourceName, seeders = seeders, url = url)

    // M-S5: merged plugin groups — sourceName must not outrank language.

    @Test
    fun groupedDisplay_prefOn_englishFromLaterScraperRanksFirst() {
        val alphaJp = stream("Show S01E01 1080p", sourceName = "Alpha", seeders = 900)
        val zebraEn = englishDub("Show S01E01 1080p", sourceName = "Zebra", seeders = 10)

        val sorted = listOf(alphaJp, zebraEn).sortedForGroupedDisplay(preferEnglishAudio = true)

        assertEquals(listOf(zebraEn, alphaJp), sorted)
    }

    @Test
    fun groupedDisplay_prefOn_withinEnglishTierKeepsScraperThenSeedersOrder() {
        val alphaJp = stream("Show S01E01 1080p", sourceName = "Alpha", seeders = 999)
        val zebraEn = englishDub("Show S01E01 1080p", sourceName = "Zebra", seeders = 5)
        val alphaEnLow = englishDub("Show S01E01 1080p", sourceName = "Alpha", seeders = 7)
        val alphaEnHigh = englishDub("Show S01E01 720p", sourceName = "Alpha", seeders = 50)

        val sorted = listOf(alphaJp, zebraEn, alphaEnLow, alphaEnHigh)
            .sortedForGroupedDisplay(preferEnglishAudio = true)

        assertEquals(listOf(alphaEnHigh, alphaEnLow, zebraEn, alphaJp), sorted)
    }

    @Test
    fun groupedDisplay_prefOff_orderIsUnchangedFromLegacyComparator() {
        val zebraEn = englishDub("Show S01E01 1080p", sourceName = "Zebra", seeders = 10)
        val betaLow = stream("Show S01E01 1080p B", sourceName = "Beta", seeders = 5)
        val alphaLow = stream("Show S01E01 1080p A", sourceName = "Alpha", seeders = 5)
        val alphaHigh = stream("Show S01E01 1080p A2", sourceName = "Alpha", seeders = 50)

        val sorted = listOf(zebraEn, betaLow, alphaLow, alphaHigh)
            .sortedForGroupedDisplay(preferEnglishAudio = false)

        // Legacy order: sourceName groups, then seeders desc — the English
        // stream stays in its "Zebra" slot at the end.
        assertEquals(listOf(alphaHigh, alphaLow, betaLow, zebraEn), sorted)
    }

    // M-S6: embedded groups go through sortedDubFirstForDisplay.

    @Test
    fun embeddedGroup_prefOn_sortedDubFirst() {
        val jpWellSeeded = stream("Show S01E01 1080p", seeders = 900)
        val enPoorlySeeded = englishDub("Show S01E01 1080p", seeders = 10)
        val jpMid = stream("Show S01E01 720p", seeders = 100)
        val group = AddonStreamGroup(
            addonName = "Embedded",
            addonId = "embedded",
            streams = listOf(jpWellSeeded, enPoorlySeeded, jpMid),
        )

        val sorted = group.sortedDubFirstForDisplay(preferEnglishAudio = true)

        assertEquals(listOf(enPoorlySeeded, jpWellSeeded, jpMid), sorted.streams)
    }

    @Test
    fun embeddedGroup_prefOff_groupReturnedUntouched() {
        val group = AddonStreamGroup(
            addonName = "Embedded",
            addonId = "embedded",
            streams = listOf(
                stream("Show S01E01 1080p", seeders = 900),
                englishDub("Show S01E01 1080p", seeders = 10),
            ),
        )

        assertSame(group, group.sortedDubFirstForDisplay(preferEnglishAudio = false))
    }

    // M-S4: the post-presentation partition restores dub-first across blocks.

    @Test
    fun partition_prefOn_englishFirstAcrossBlocks_preservingPresentedOrderWithinTiers() {
        val jpDebrid = stream("JP debrid")
        val jpDirect = stream("JP direct")
        val enDirect = englishDub("EN direct")
        val enDebrid = englishDub("EN debrid")
        val jpDirect2 = stream("JP direct 2")
        val group = AddonStreamGroup(
            addonName = "Addon",
            addonId = "addon:test",
            streams = listOf(jpDebrid, jpDirect, enDirect, enDebrid, jpDirect2),
        )

        val partitioned = group.partitionedDubFirstForDisplay(preferEnglishAudio = true)

        assertEquals(listOf(enDirect, enDebrid, jpDebrid, jpDirect, jpDirect2), partitioned.streams)
    }

    @Test
    fun partition_prefOff_groupReturnedUntouched() {
        val group = AddonStreamGroup(
            addonName = "Addon",
            addonId = "addon:test",
            streams = listOf(
                stream("JP debrid"),
                englishDub("EN direct"),
            ),
        )

        assertSame(group, group.partitionedDubFirstForDisplay(preferEnglishAudio = false))
    }

    @Test
    fun debridPresentationThenPartition_prefOn_englishPassthroughBeatsNonEnglishDebrid() {
        val jpDebrid = StreamItem(
            name = "Show S01E01 1080p",
            infoHash = "abcdef1234567890abcdef1234567890abcdef12",
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(
                filename = "Show.S01E01.1080p.WEB-DL.HEVC-GRP.mkv",
                videoSize = 10_000_000_000,
            ),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = DebridProviders.TORBOX_ID,
                providerName = DebridProviders.Torbox.displayName,
                state = StreamDebridCacheState.CACHED,
                cachedName = "Show.S01E01.1080p.WEB-DL.HEVC-GRP.mkv",
                cachedSize = 10_000_000_000,
            ),
        )
        val enDirect = StreamItem(
            name = "Show S01E01 [English Dub] 1080p",
            url = "https://example.test/show-s01e01.mp4",
            addonName = "Addon",
            addonId = "addon:test",
        )
        val group = AddonStreamGroup(
            addonName = "Addon",
            addonId = "addon:test",
            streams = listOf(enDirect, jpDebrid),
        )
        val settings = DebridSettings(
            enabled = true,
            providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
        )

        // Presentation alone regroups debrid-first: the JP debrid stream
        // jumps above the English direct stream (the M-S4 bug). The debrid
        // stream itself may be reformatted by presentation, so compare
        // against the presented elements rather than the raw fixtures.
        val presented = DebridStreamPresentation.apply(listOf(group), settings).single()
        assertEquals(2, presented.streams.size)
        val presentedDebrid = presented.streams.first()
        assertEquals(enDirect, presented.streams[1])

        // …and the partition the repositories now run afterwards restores
        // dub-first across the blocks.
        val partitioned = presented.partitionedDubFirstForDisplay(preferEnglishAudio = true)
        assertEquals(listOf(enDirect, presentedDebrid), partitioned.streams)

        // Preference off: the presented order is published exactly as-is.
        assertSame(presented, presented.partitionedDubFirstForDisplay(preferEnglishAudio = false))
    }
}
