package com.nuvio.app.features.streams

import com.nuvio.app.core.device.DataSaverGating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveStreamPickerTest {

    private fun stream(
        name: String,
        description: String = "",
        addonName: String = "Torrentio",
    ) = StreamItem(
        name = name,
        description = description,
        addonName = addonName,
        addonId = "torrentio",
    )

    // targetQuality

    @Test
    fun targetQuality_wifiFullBattery_is1080p() {
        assertEquals("1080p", AdaptiveStreamPicker.targetQuality(isCellular = false, batteryPercent = 80, dataSaverActive = false))
    }

    @Test
    fun targetQuality_cellular_is480p() {
        assertEquals("480p", AdaptiveStreamPicker.targetQuality(isCellular = true, batteryPercent = 80, dataSaverActive = false))
    }

    @Test
    fun targetQuality_lowBatteryOverridesWifi() {
        assertEquals("480p", AdaptiveStreamPicker.targetQuality(isCellular = false, batteryPercent = 24, dataSaverActive = false))
        assertEquals("480p", AdaptiveStreamPicker.targetQuality(isCellular = false, batteryPercent = 0, dataSaverActive = false))
    }

    @Test
    fun targetQuality_battery25IsNotLow() {
        assertEquals("1080p", AdaptiveStreamPicker.targetQuality(isCellular = false, batteryPercent = 25, dataSaverActive = false))
    }

    @Test
    fun targetQuality_unknownBatteryTreatedAsNotLow() {
        assertEquals("1080p", AdaptiveStreamPicker.targetQuality(isCellular = false, batteryPercent = -1, dataSaverActive = false))
    }

    @Test
    fun targetQuality_dataSaverOverridesWifi() {
        assertEquals("480p", AdaptiveStreamPicker.targetQuality(isCellular = false, batteryPercent = 90, dataSaverActive = true))
    }

    // parseSeeders / parseSizeBytes

    @Test
    fun parseSeeders_torrentioFormat() {
        val s = stream("x", "Avatar.S01E01.1080p.WEB-DL\n\uD83D\uDC64 500 \uD83D\uDCBE 2.1 GB \u2699\uFE0F ThePirateBay")
        assertEquals(500, AdaptiveStreamPicker.parseSeeders(s))
    }

    @Test
    fun parseSeeders_missing_returnsNull() {
        assertNull(AdaptiveStreamPicker.parseSeeders(stream("Some.Release.1080p")))
    }

    @Test
    fun parseSizeBytes_gigabytes() {
        val s = stream("x", "\uD83D\uDC64 100 \uD83D\uDCBE 2.5 GB")
        assertEquals(2_684_354_560L, AdaptiveStreamPicker.parseSizeBytes(s))
    }

    @Test
    fun parseSizeBytes_megabytes() {
        val s = stream("x", "\uD83D\uDCBE 700 MB")
        assertEquals(734_003_200L, AdaptiveStreamPicker.parseSizeBytes(s))
    }

    @Test
    fun parseSizeBytes_missing_returnsNull() {
        assertNull(AdaptiveStreamPicker.parseSizeBytes(stream("Some.Release.1080p")))
    }

    // streamResolution

    @Test
    fun streamResolution_detectsLabels() {
        assertEquals("1080p", stream("Show.S01E01.1080p.WEB-DL").streamResolution())
        assertEquals("720p", stream("Show.S01E01.720p.HDTV").streamResolution())
        assertEquals("480p", stream("Show.S01E01.480p.WEB-DL").streamResolution())
        assertEquals("2160p", stream("Show.S01E01.2160p.WEB-DL.DV").streamResolution())
        assertNull(stream("Show.S01E01.WEB-DL").streamResolution())
    }

    // pickBestSeeded

    @Test
    fun pickBestSeeded_prefersHighestSeedersAtTargetQuality() {
        val streams = listOf(
            stream("A.1080p", "\uD83D\uDC64 10 \uD83D\uDCBE 2.0 GB"),
            stream("B.1080p", "\uD83D\uDC64 400 \uD83D\uDCBE 2.2 GB"),
            stream("C.720p", "\uD83D\uDC64 9999 \uD83D\uDCBE 1.0 GB"),
        )
        assertEquals("B.1080p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun pickBestSeeded_skipsAbsurdOutliersWhenSaneExists() {
        val streams = listOf(
            stream("Remux.1080p", "\uD83D\uDC64 900 \uD83D\uDCBE 18.5 GB"),
            stream("Encode.1080p", "\uD83D\uDC64 120 \uD83D\uDCBE 2.4 GB"),
        )
        assertEquals("Encode.1080p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun pickBestSeeded_fallsBackToClosestQualityPreferringLower() {
        val streams = listOf(
            stream("A.720p", "\uD83D\uDC64 50 \uD83D\uDCBE 1.2 GB"),
            stream("B.2160p", "\uD83D\uDC64 60 \uD83D\uDCBE 8.0 GB"),
        )
        // 1080p missing: 720p and 2160p are equidistant → prefer the lower one.
        assertEquals("A.720p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun pickBestSeeded_unknownSeedersRankBelowKnown() {
        val streams = listOf(
            stream("A.1080p", "\uD83D\uDCBE 2.0 GB"),
            stream("B.1080p", "\uD83D\uDC64 5 \uD83D\uDCBE 2.1 GB"),
        )
        assertEquals("B.1080p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun pickBestSeeded_empty_returnsNull() {
        assertNull(AdaptiveStreamPicker.pickBestSeeded(emptyList(), "1080p"))
    }

    @Test
    fun pickBestSeeded_tieKeepsAddonOrder() {
        val streams = listOf(
            stream("First.1080p", "\uD83D\uDC64 100 \uD83D\uDCBE 2.0 GB"),
            stream("Second.1080p", "\uD83D\uDC64 100 \uD83D\uDCBE 2.0 GB"),
        )
        assertEquals("First.1080p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun dataSaverGating_preloadRules() {
        // Documents the contract the downloads worker relies on: preload gating is
        // isWifi() && batteryPercent() > 25 && !dataSaverActive.
        assertTrue(DataSaverGating.shouldPreload(isWifi = true, batteryPercent = 80, dataSaverActive = false))
        assertTrue(DataSaverGating.shouldPreload(isWifi = true, batteryPercent = 26, dataSaverActive = false))
        assertTrue(!DataSaverGating.shouldPreload(isWifi = false, batteryPercent = 80, dataSaverActive = false))
        assertTrue(!DataSaverGating.shouldPreload(isWifi = true, batteryPercent = 25, dataSaverActive = false))
        assertTrue(!DataSaverGating.shouldPreload(isWifi = true, batteryPercent = -1, dataSaverActive = false))
        assertTrue(!DataSaverGating.shouldPreload(isWifi = true, batteryPercent = 80, dataSaverActive = true))
    }

    // hasEnglishAudio / dub-over-sub preference

    @Test
    fun hasEnglishAudio_detectsStandaloneMultiAndDual() {
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Re.Zero.S2.MULTi.1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL-AUDIO.1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.1080p.DUBBED")))
    }

    @Test
    fun hasEnglishAudio_scansBehaviorHintsFilename() {
        val s = StreamItem(
            name = "Re.Zero S2",
            addonName = "Torrentio",
            addonId = "torrentio",
            behaviorHints = StreamBehaviorHints(filename = "Re.Zero.S2E01.1080p.WEB.H.264-ENG"),
        )
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(s))
    }

    @Test
    fun hasEnglishAudio_subtitleMentionIsNotAudio() {
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.1080p English subtitles")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.1080p")))
    }

    @Test
    fun pickBestSeeded_englishDubBeatsHdrSeederGate() {
        // The only English dub is HDR with few known seeders; the Japanese stream
        // is well-seeded. Dub over sub, always — the dub must win.
        val streams = listOf(
            stream("JP.1080p.HDR", "\uD83D\uDC64 900 \uD83D\uDCBE 3.0 GB"),
            stream("EN.MULTi.1080p.HDR", "\uD83D\uDC64 10 \uD83D\uDCBE 3.0 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true, requireHdrSeeders = true,
        )
        assertEquals("EN.MULTi.1080p.HDR", picked?.name)
    }

    @Test
    fun pickBestSeeded_noEnglish_fallsBackToBestSub() {
        val streams = listOf(
            stream("JP.A.1080p", "\uD83D\uDC64 10 \uD83D\uDCBE 2.0 GB"),
            stream("JP.B.1080p", "\uD83D\uDC64 500 \uD83D\uDCBE 2.1 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true, requireHdrSeeders = true,
        )
        assertEquals("JP.B.1080p", picked?.name)
    }
}
