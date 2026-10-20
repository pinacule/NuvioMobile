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
    fun hasEnglishAudio_detectsBareDubAndEnglishAudioTags() {
        // Bare [DUB] / EN DUB tags (Crunchyroll-style rips).
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [DUB] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [EN DUB] 720p")))
        // "English audio" phrasings.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [English Audio] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [EN Audio] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio 1080p")))
    }

    @Test
    fun hasEnglishAudio_detectsComboLanguageTags() {
        // "eng" joined to another language code by a separator has no word
        // boundary next to the "x", so a plain \beng\b check misses these.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 (BD 1080p) [JPNxENG]")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [ENGxJPN] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN/ENG] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN.ENG] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [ENG+JPN] 1080p")))
    }

    @Test
    fun hasEnglishAudio_multiSubtitleTagsAreNotEnglishAudio() {
        // Japanese releases carrying many subtitle tracks: "Multi-Subs" is a
        // subtitle tag, not multi-audio. (Standalone MULTi/DUAL without a
        // subtitle tag stays an English-audio signal — see
        // hasEnglishAudio_detectsStandaloneMultiAndDual.)
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[Erai-raws] Show S01E01 [720p][Multi-Subs]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[philosophy] Show S1 [BD 1080p][FLAC][Multi Subs]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01 [MULTI SUBS][JPN]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[Erai-raws] Show S01E01 [Multiple Subtitle]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show BDRip x265 [ENG Sub]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[SubsPlease] Show S01E01 (1080p)")))
    }

    @Test
    fun hasEnglishAudio_dubTagBeforeSubtitleTagStillCounts() {
        // Regression: the subtitle-span strip used to run before every check,
        // and its eng...subs span ate the dub signal whenever a subtitle tag
        // followed an "English..."-style dub tag. The strong / standalone-dub /
        // combo checks now run on unstripped (or only token-stripped) text.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("[Judas] Re:Zero [English Dub][Multi Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show [English Audio][Multi Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show English Dub [ENG Sub] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show English Dubbed [Multi Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show ENG DUB [Multi Subs] 1080p")))
        // Same bug class for a combined language tag in front of a subtitle tag.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPNxENG][Multi Subs] 1080p")))
    }

    @Test
    fun hasEnglishAudio_engSubtitleTagsAreNotComboTags() {
        // "ENG.SUB" and the tail of "JPN.ENG.SUB" mark English subtitles:
        // "sub" must never be read as the partner of a combined audio tag.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [ENG.SUB] 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.ENG.SUB.1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 JPN.ENG.SUB 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 ENGxSUB 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 SUBxENG 1080p")))
    }

    @Test
    fun hasEnglishAudio_bareDualNextToSubtitleTagsStillCounts() {
        // The standalone dual/multi check strips only the multi-subtitle tag
        // tokens, never a span, so a bare "Dual" beside subtitle tags keeps
        // its meaning (the span strip could have swallowed it).
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual [ENG Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 ENG Dual Subs 1080p")))
    }

    @Test
    fun hasEnglishAudio_descriptionProseCannotFlagByItself() {
        // Strong/standalone indicators no longer scan the description, so
        // prose about audio options can't flag a Japanese release...
        val proseOnly = stream(
            "Show S01E01 1080p",
            "This release has multi audio options (JP only) and dual audio tracks",
        )
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(proseOnly))
        // ...but a genuine "eng" language tag in the description still counts.
        val engTag = stream("Show S01E01 1080p", "Audio: JPN, ENG")
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(engTag))
    }

    @Test
    fun shouldPreferEnglishAudio_matchesPrimarySubtag() {
        assertTrue(AdaptiveStreamPicker.shouldPreferEnglishAudio("en"))
        assertTrue(AdaptiveStreamPicker.shouldPreferEnglishAudio("en-US"))
        assertTrue(AdaptiveStreamPicker.shouldPreferEnglishAudio("en-gb"))
        assertTrue(AdaptiveStreamPicker.shouldPreferEnglishAudio(" EN "))
        // Explicit non-language choices stay disabled, as do other languages.
        assertFalse(AdaptiveStreamPicker.shouldPreferEnglishAudio("device"))
        assertFalse(AdaptiveStreamPicker.shouldPreferEnglishAudio("original"))
        assertFalse(AdaptiveStreamPicker.shouldPreferEnglishAudio("default"))
        assertFalse(AdaptiveStreamPicker.shouldPreferEnglishAudio("ja"))
        assertFalse(AdaptiveStreamPicker.shouldPreferEnglishAudio(""))
    }

    @Test
    fun pickBestSeeded_multiSubsJapaneseDoesNotBeatRealDub() {
        // Regression: the well-seeded Japanese "Multi-Subs" release used to be
        // classified as English audio and won on seeders; now the real dub
        // (even at a lower quality) must win.
        val streams = listOf(
            stream("JP.Multi-Subs.1080p", "\uD83D\uDC64 900 \uD83D\uDCBE 2.0 GB"),
            stream("EN.Dual.Audio.720p", "\uD83D\uDC64 50 \uD83D\uDCBE 1.0 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true,
        )
        assertEquals("EN.Dual.Audio.720p", picked?.name)
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
