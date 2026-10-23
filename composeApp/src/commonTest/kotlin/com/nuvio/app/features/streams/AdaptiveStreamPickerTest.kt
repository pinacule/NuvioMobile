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

    private fun streamWithParsed(
        name: String,
        languages: List<String> = emptyList(),
        audio: List<String> = emptyList(),
    ) = StreamItem(
        name = name,
        addonName = "Torrentio",
        addonId = "torrentio",
        clientResolve = StreamClientResolve(
            stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(
                    parsed = StreamClientResolveParsed(
                        languages = languages,
                        audio = audio,
                    ),
                ),
            ),
        ),
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
    fun parseSeeders_kAndMSuffixes() {
        // Suffixed counts are decimal: "1.5K" = 1500, "1.5M" = 1,500,000.
        assertEquals(1500, AdaptiveStreamPicker.parseSeeders(stream("x", "Release \uD83D\uDC64 1.5K")))
        assertEquals(2000, AdaptiveStreamPicker.parseSeeders(stream("x", "Release 2K seeders")))
        assertEquals(1_500_000, AdaptiveStreamPicker.parseSeeders(stream("x", "Release \uD83D\uDC65 1.5M")))
        assertEquals(250, AdaptiveStreamPicker.parseSeeders(stream("x", "Release 0.25K seeders")))
    }

    @Test
    fun parseSeeders_suffixMustNotBeAWordHead() {
        // "500 MB" is a size, not 500M seeders: the plain count still parses.
        assertEquals(500, AdaptiveStreamPicker.parseSeeders(stream("x", "Release \uD83D\uDC64 500 MB")))
        // Plain thousands separators keep working in both spellings.
        assertEquals(1500, AdaptiveStreamPicker.parseSeeders(stream("x", "Release \uD83D\uDC64 1,500")))
        assertEquals(1500, AdaptiveStreamPicker.parseSeeders(stream("x", "Release 1.500 seeders")))
    }

    @Test
    fun oversizeHelpersUseTheTierCeilings() {
        val oversize720 = stream("Show 720p", "Release \uD83D\uDCBE 30 GB")
        val sane720 = stream("Show 720p", "Release \uD83D\uDCBE 1.5 GB")
        val unknownSize = stream("Show 720p", "Release")
        assertTrue(AdaptiveStreamPicker.isKnownOversize(oversize720))
        assertFalse(AdaptiveStreamPicker.isKnownOversize(sane720))
        // Unknown size never counts as oversize.
        assertFalse(AdaptiveStreamPicker.isKnownOversize(unknownSize))
        // The least-oversize member wins the all-oversize fallback.
        val lessOversize720 = stream("Show 720p", "Release \uD83D\uDCBE 3 GB")
        assertEquals(lessOversize720, AdaptiveStreamPicker.pickLeastOversize(listOf(oversize720, lessOversize720), "720p"))
        assertNull(AdaptiveStreamPicker.pickLeastOversize(listOf(sane720), "720p"))
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
        assertEquals("360p", stream("Show.S01E01.360p.WEB-DL").streamResolution())
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

    // pickBestSeeded — seeder floor (audit P2)

    @Test
    fun pickBestSeeded_belowFloorAtTargetTier_healthyNeighbourTierWins() {
        // The audit's playback scenario: on a 480p target (cellular / data
        // saver), the only 480p torrent is nearly dead while a healthy 720p
        // exists. The floor makes the well-seeded neighbour tier win instead
        // of auto-playing the dying swarm.
        val streams = listOf(
            stream("Dead.480p", "\uD83D\uDC64 2 \uD83D\uDCBE 700 MB"),
            stream("Healthy.720p", "\uD83D\uDC64 300 \uD83D\uDCBE 1.5 GB"),
        )
        assertEquals("Healthy.720p", AdaptiveStreamPicker.pickBestSeeded(streams, "480p")?.name)
    }

    @Test
    fun pickBestSeeded_allBelowFloor_picksBestSeededWeak() {
        // Every candidate is below the floor: the floor is a preference,
        // never a no-pick — the best-seeded weak candidate wins.
        val streams = listOf(
            stream("Weak.480p", "\uD83D\uDC64 2 \uD83D\uDCBE 700 MB"),
            stream("Weak.720p", "\uD83D\uDC64 8 \uD83D\uDCBE 1.5 GB"),
        )
        assertEquals("Weak.720p", AdaptiveStreamPicker.pickBestSeeded(streams, "480p")?.name)
    }

    @Test
    fun pickBestSeeded_allBelowFloorSameTier_picksBestInTier() {
        val streams = listOf(
            stream("A.1080p", "\uD83D\uDC64 1 \uD83D\uDCBE 2.0 GB"),
            stream("B.1080p", "\uD83D\uDC64 6 \uD83D\uDCBE 2.1 GB"),
        )
        assertEquals("B.1080p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun pickBestSeeded_atFloorCountsHealthy() {
        // Exactly at the floor the target tier is healthy: it wins over a
        // far better seeded neighbour tier (quality proximity still rules
        // among healthy picks).
        val streams = listOf(
            stream("Floor.480p", "\uD83D\uDC64 10 \uD83D\uDCBE 700 MB"),
            stream("Healthy.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 1.5 GB"),
        )
        assertEquals("Floor.480p", AdaptiveStreamPicker.pickBestSeeded(streams, "480p")?.name)
    }

    @Test
    fun pickBestSeeded_unknownSeedersAreNeutral_keepTargetTier() {
        // Unknown seeder counts are neutral, not zero: an uncounted stream
        // at the target tier is a healthy pick and keeps the tier.
        val streams = listOf(
            stream("Unknown.480p", "\uD83D\uDCBE 700 MB"),
            stream("Healthy.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 1.5 GB"),
        )
        assertEquals("Unknown.480p", AdaptiveStreamPicker.pickBestSeeded(streams, "480p")?.name)
    }

    @Test
    fun pickBestSeeded_knownBelowFloorLosesToUnknownOneTierAway() {
        // Known-bad evidence (3 seeders) loses to no evidence (unknown
        // count) one tier away.
        val streams = listOf(
            stream("Dead.480p", "\uD83D\uDC64 3 \uD83D\uDCBE 700 MB"),
            stream("Unknown.720p", "\uD83D\uDCBE 1.5 GB"),
        )
        assertEquals("Unknown.720p", AdaptiveStreamPicker.pickBestSeeded(streams, "480p")?.name)
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

    // hasEnglishAudio — named-language vetoes (audit M-S1 / LOW #13)

    @Test
    fun hasEnglishAudio_namedNonEnglishDubIsNotEnglish() {
        // A dub tag next to a named non-English language is THAT language's
        // dub, not the English one.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Re Zero S01E01 Hindi Dub 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("The Simpsons S01E01 JPN DUB 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Hindi Dubbed 1080p")))
        // A bare dub tag with NO language named still counts (anime convention)...
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [DUB] 1080p")))
        // ...and so does a dub where English is named alongside the other language.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 JPN ENG Dub 1080p")))
    }

    @Test
    fun hasEnglishAudio_dualAudioListingOnlyNonEnglishLanguagesIsNotEnglish() {
        // Dual/multi audio implies English only while no language list says
        // otherwise; an explicit list without English settles it.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Re Zero S01E01 Dual Audio JPN POR 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Multi Audio JPN 1080p")))
        // English among the listed languages keeps it a dub.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio JPN ENG 1080p")))
        // No languages listed: plain "Dual Audio" still counts (existing rule).
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio 1080p")))
    }

    // hasEnglishAudio — subtitle-bound languages and the veto (review fix)

    @Test
    fun hasEnglishAudio_subtitleBoundEnglishDoesNotRescueNonEnglishDub() {
        // The ENG/EN here belongs to a Subs tag: it describes subtitle
        // tracks, not audio, so it must not cancel the JPN veto — the dub
        // is Japanese.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN Dub][ENG Subs] 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 JPN DUB ENG SUBS 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 JPN Dub EN Subs 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN Dub][EN Subs] 1080p")))
    }

    @Test
    fun hasEnglishAudio_subtitleBoundNonEnglishDoesNotVetoNeutralTags() {
        // Mirror image: a JPN bound to a Subs tag describes subtitles, so
        // it must not veto a bare [DUB] or a Dual Audio tag (regression —
        // both counted as English audio before the veto existed in this
        // form).
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [DUB] [JPN Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio [JPN Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [DUB] [ENG Subs] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [ENG Dub][JPN Subs] 1080p")))
        // A JPN that is NOT subtitle-bound still vetoes...
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [DUB][JPN Audio]")))
        // ...and an audio-bound JPN is not rescued by a subtitle ENG.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN Audio][ENG Subs]")))
    }

    @Test
    fun hasEnglishAudio_additionalVetoLanguages() {
        // Spanish/Latin-American dub tags and the pt/bul codes name their
        // language just like Hindi/JPN do: the dub is not English.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Latino Dub 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Castellano Dub 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Espanol Dub 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 PT-BR Dub 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 BUL Dub 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio Latino 1080p")))
    }

    @Test
    fun hasEnglishAudio_frenchFusedTokensVetoMultiReleases() {
        // French scene releases name their audio with fused tokens
        // (VFF / VFQ / TrueFrench) that the french|fra|fre veto
        // never matched, so a MULTi French release was flagged English.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[Judas] Re:Zero S02E01 MULTi VFF 1080p")))
        // The veto lands wherever the French token sits, including the
        // positions where the standalone MULTi tag itself is in tag
        // position (directly before the resolution / trailing).
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 MULTi 1080p VFF")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 VFQ MULTi 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 TrueFrench MULTi 1080p")))
        // VOSTFR is NOT a French-audio claim: it means original
        // (unspecified-language) audio with French SUBTITLES, so it must
        // not veto neutral English evidence — these are English-original
        // releases carrying French subtitles.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Movie 2024 Dual Audio VOSTFR 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Movie VOSTFR MULTi 1080p")))
        // A genuine MULTi release that names English still counts.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 MULTi ENG 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 MULTi English Dub 1080p")))
    }

    @Test
    fun hasEnglishAudio_standaloneDualFollowedByYearOrChannelsIsTagPosition() {
        // A channel layout or a year sits where a resolution tag would:
        // both are tag position for a standalone dual/multi token.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual 5.1 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual 7.1 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show DUAL 2024 1080p")))
        // An ordinary title word after "Dual" still is not a tag.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Destinies 1080p")))
    }

    // hasEnglishAudio — underscore-joined filenames (audit M-S2)

    @Test
    fun hasEnglishAudio_underscoreJoinedFilename() {
        // "_" is a regex word character, so without normalization these
        // filenames read as one long word and every tag check missed.
        val englishDub = StreamItem(
            name = "Re Zero - 01 (1080p)",
            addonName = "Torrentio",
            addonId = "torrentio",
            behaviorHints = StreamBehaviorHints(filename = "Re_Zero_01_English_Dub_1080p.mkv"),
        )
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(englishDub))
        val dualAudio = StreamItem(
            name = "Re Zero - 01 (1080p)",
            addonName = "Torrentio",
            addonId = "torrentio",
            behaviorHints = StreamBehaviorHints(filename = "Re_Zero_01_Dual_Audio_1080p.mkv"),
        )
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(dualAudio))
    }

    // hasEnglishAudio — structured parsed metadata (clientResolve.stream.raw.parsed)

    @Test
    fun hasEnglishAudio_parsedLanguagesNamingEnglishCountsWithoutTitleTags() {
        // Clean title, no dub/multi tag anywhere: the parsed language
        // listing alone is positive evidence when it names English.
        assertTrue(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 1080p", languages = listOf("ja", "en")),
            ),
        )
        assertTrue(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 1080p", languages = listOf("English")),
            ),
        )
        // English named only in the parsed audio list counts too.
        assertTrue(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 1080p", audio = listOf("English")),
            ),
        )
    }

    @Test
    fun hasEnglishAudio_parsedLanguagesWithoutEnglishVetoTitleTags() {
        // A parsed listing that names only non-English languages vetoes
        // the release regardless of its title tags: the dub is Spanish.
        assertFalse(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 [DUB] 1080p", languages = listOf("es")),
            ),
        )
        assertFalse(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 English Dub 1080p", languages = listOf("ja")),
            ),
        )
        assertFalse(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 1080p", languages = listOf("ja")),
            ),
        )
    }

    @Test
    fun hasEnglishAudio_absentOrNeutralParsedMetadataKeepsTextBehaviour() {
        // No parsed languages named (empty listing, or only the neutral
        // "multi" marker): the text checks decide exactly as before, so a
        // bare [DUB] still counts and a clean title still does not.
        assertTrue(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 [DUB] 1080p", languages = emptyList()),
            ),
        )
        assertTrue(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 [DUB] 1080p", languages = listOf("multi")),
            ),
        )
        assertFalse(
            AdaptiveStreamPicker.hasEnglishAudio(
                streamWithParsed("Show S01E01 1080p", languages = emptyList()),
            ),
        )
    }

    // hasEnglishAudio — subtitle strip span (audit M-S3)

    @Test
    fun hasEnglishAudio_engTagFarFromTrailingSubsIsStillSubtitleMention() {
        // The ENG sits 19 chars from the trailing "Subs" (resolution/codec
        // tokens in between) — beyond the old 16-char strip span, it leaked
        // through the "eng" fallback as a fake audio tag.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Re Zero S01E01 ENG 1080p WEB-DL x264 Subs")))
    }

    // hasEnglishAudio — episode-title words (audit LOW #8)

    @Test
    fun hasEnglishAudio_episodeTitleWordsAreNotTags() {
        // "Dual" here is an episode-title word, followed by another ordinary
        // word — not a release tag. (Real standalone tags sit in tag
        // position: followed by a separator, another tag, a resolution, a
        // group suffix, or the end of the text.)
        val titled = StreamItem(
            name = "Torrentio",
            title = "Re Zero - 01 - Dual Destinies (1080p) [English Subtitles]",
            addonName = "Torrentio",
            addonId = "torrentio",
        )
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(titled))
        assertFalse(
            AdaptiveStreamPicker.hasEnglishAudio(
                stream("Re Zero - 01 - Dual Destinies (1080p) [English Subtitles]"),
            ),
        )
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL-GRP")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 DUAL")))
    }

    // hasEnglishAudio — description-only dub phrases (audit LOW #9)

    @Test
    fun hasEnglishAudio_descriptionEnglishDubPhrase() {
        // The dub is stated ONLY in the description, as an explicit phrase.
        val descOnly = StreamItem(
            name = "Torrentio 1080p",
            title = "Re Zero - 01",
            description = "English Dub | DDP5.1",
            addonName = "Torrentio",
            addonId = "torrentio",
        )
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(descOnly))
        // "English subtitles" in a description is not a dub phrase.
        val subsOnly = StreamItem(
            name = "Torrentio 1080p",
            title = "Re Zero - 01",
            description = "English subtitles",
            addonName = "Torrentio",
            addonId = "torrentio",
        )
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(subsOnly))
    }

    // hasEnglishAudio — bare language tags (audit LOW #10)

    @Test
    fun hasEnglishAudio_bareEnglishTagAndDotSeparatedEnAudio() {
        // Tag-position bare "English" (after the episode marker / year).
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Re.Zero.S01E01.English.1080p.WEB-DL")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Movie.Name.2020.English.1080p")))
        // Dot-separated "EN.Audio".
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Re.Zero.S01E01.EN.Audio.1080p")))
        // "English" inside the show's name (before every tag anchor) is not a tag.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("The.English.Game.S01E01.1080p.WEB-DL")))
    }

    // hasEnglishAudio — batch-18 regression battery (remaining cases)

    @Test
    fun hasEnglishAudio_batch18RegressionBattery() {
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("[Multi-Subs][English Dub] Show 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [Multi Subs]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN Audio][ENG Subs]")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 MULTiSUBS 1080p")))
        // "Subs: ENG" stated in the description is a subtitle line, not audio.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 1080p", "Subs: ENG")))
    }

    // pickBestSeeded — unparseable resolutions (audit LOW #11)

    @Test
    fun pickBestSeeded_unparseableResolutionDubIsSelectableAsLastTier() {
        // The only English dub carries no resolution token anywhere; the
        // well-seeded Japanese release does. The dub must still win —
        // previously the unknown-resolution pool was unreachable.
        val streams = listOf(
            stream("JP.1080p", "\uD83D\uDC64 900 \uD83D\uDCBE 2.0 GB"),
            stream("Show S01E01 English Dub", "\uD83D\uDC64 10 \uD83D\uDCBE 1.0 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true,
        )
        assertEquals("Show S01E01 English Dub", picked?.name)
    }

    @Test
    fun pickBestSeeded_parseableBeatsUnparseableWithinSameLanguagePool() {
        // The unparseable dub has far more seeders, but a parseable
        // candidate in the same (English) pool always outranks the last tier.
        val streams = listOf(
            stream("EN Dub Show S01E01", "\uD83D\uDC64 999 \uD83D\uDCBE 1.0 GB"),
            stream("EN.720p.Dub", "\uD83D\uDC64 5 \uD83D\uDCBE 1.0 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true,
        )
        assertEquals("EN.720p.Dub", picked?.name)
    }

    @Test
    fun pickBestSeeded_allUnparseableResolutions_picksBestSeeded() {
        val streams = listOf(
            stream("A Show S01E01", "\uD83D\uDC64 10 \uD83D\uDCBE 1.0 GB"),
            stream("B Show S01E01", "\uD83D\uDC64 300 \uD83D\uDCBE 1.2 GB"),
        )
        assertEquals("B Show S01E01", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    // hasEnglishAudio — fused codec follow tokens (batch-22 R-2)

    @Test
    fun hasEnglishAudio_dualGateAcceptsFusedCodecTokens() {
        // Scene-standard dual-audio naming: the token after DUAL/MULTI
        // fuses the codec and the channel count ("DDP5.1" extracts as
        // "ddp5"), which the follow-token set lookup used to miss, so these
        // releases classified as not-English and dub-first never applied.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.DDP5.1.1080p.WEB.H.264-GRP")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.DD5.1.1080p.WEB-DL-GRP")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.EAC3.1080p.WEB-DL-GRP")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.MULTi.DDP5.1.1080p.WEB-DL-GRP")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.H.264.1080p.WEB-DL")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.AMZN.WEB-DL.1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 DUAL DDP5 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual TrueHD 1080p")))
        // An ordinary title word after "Dual" still is not a tag — even
        // one that starts with a codec string.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Carriage 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Ddramas 1080p")))
    }

    @Test
    fun hasEnglishAudio_dualGateAcceptsHdrFollowTokens() {
        // HDR tags sit in the same slot as codec tags in UHD dual-audio
        // names; the follow set knew "hdr"/"dv" but not "hdr10"/"hdr10plus",
        // so these classified as not-English.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.HDR10.DDP5.1.2160p.WEB-DL")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.MULTi.HDR10.DDP5.1.2160p.WEB-DL")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 DUAL HDR10 DDP5.1 1080p")))
        // Token extraction stops at "+", so "HDR10+" arrives as "hdr10".
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.DUAL.HDR10+.DDP5.1.2160p.WEB-DL")))
    }

    // hasEnglishAudio — subtitle clusters with intervening tokens (batch-22 R-5)

    @Test
    fun hasEnglishAudio_subtitleStripToleratesInterveningTokens() {
        // A subtitle tag may name a whole language cluster: other language
        // tokens or "Multi" sit between the language and the Subs token,
        // and every token of the cluster is subtitle-bound.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[JPN Dub][ENG Multi Subs] Show S01E01 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("[SPA Dub][ENG/JPN Subs] Show S01E01 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 JPN Dub ENG Multi Subs 1080p")))
        // A subtitle-bound cluster must not form a combined audio tag.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [JPN/ENG Subs] 1080p")))
        // Mirror image: a subtitle-bound JPN cluster must not veto a bare dub.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [DUB] [JPN Multi Subs] 1080p")))
        // ...and a real ENG dub in front of a JPN subs cluster still counts.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [ENG Dub][JPN Multi Subs] 1080p")))
    }

    // hasEnglishAudio — veto rescue discipline (batch-22 R-6)

    @Test
    fun hasEnglishAudio_vetoRescueNeedsTagPositionEvidence() {
        // Title text must not cancel a named non-English dub: "English" as
        // part of a show name, the Spanish phrase "En Espanol", and an
        // "English" in the title promoted only by an anchor in the addon
        // name.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("English.Teacher.S01E01.JPN.DUB.1080p.WEB-DL")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 En Espanol Dual Audio 1080p")))
        assertFalse(
            AdaptiveStreamPicker.hasEnglishAudio(
                StreamItem(
                    name = "Torrentio 1080p",
                    title = "The English Game S01E01 JPN DUB",
                    addonName = "Torrentio",
                    addonId = "torrentio",
                ),
            ),
        )
        // Tag-position English evidence still rescues: a bare "en" next to
        // a dub/audio token, and an anchored "English" in the filename.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio EN 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [EN DUB][JPN Audio] 1080p")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 JPN Dub EN 1080p")))
        val anchoredInFilename = StreamItem(
            name = "Show S01E01 JPN DUB",
            addonName = "Torrentio",
            addonId = "torrentio",
            behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.English.1080p.WEB-DL"),
        )
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(anchoredInFilename))
    }

    // hasEnglishAudio — veto collisions with ordinary release text (batch-22 R-13)

    @Test
    fun hasEnglishAudio_vetoSparesPartMarkersAndTitleWords() {
        // "Pt.2" is a part marker, not Portuguese.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.Pt.2.1080p.WEB-DL.Dual.Audio.x264-GRP")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.Pt.1.Dual.Audio.1080p")))
        // "Spa" as an episode-title word is not Spanish.
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S04E11.Spa.Day.1080p.WEB-DL.Dual.Audio")))
        assertTrue(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Spa Day Dual Audio 1080p")))
        // But "spa" followed by a fused codec token is tag position, so
        // the veto lands: Spanish is the only language this names.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show.S01E01.SPA.DDP5.1.Dual.Audio.1080p.WEB-DL")))
        // Real Spanish/Portuguese vetoes still land.
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 [SPA Dub] 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 Dual Audio SPA 1080p")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 SPA 1080p Dual Audio")))
        assertFalse(AdaptiveStreamPicker.hasEnglishAudio(stream("Show S01E01 PT Dub 1080p")))
    }

    // pickBestSeeded — size ceiling is a hard filter (batch-22 R-4)

    @Test
    fun pickBestSeeded_oversizeOnlyPoolReturnsNull() {
        // A 30 GB remux is the only candidate at the target quality: the
        // auto-pick yields nothing (callers fall back to the manual list)
        // instead of auto-playing it.
        val streams = listOf(stream("Remux.1080p", "\uD83D\uDC64 500 \uD83D\uDCBE 30 GB"))
        assertNull(AdaptiveStreamPicker.pickBestSeeded(streams, "1080p"))
    }

    @Test
    fun pickBestSeeded_oversizeOnlyNullTierReturnsNull() {
        // The unparseable-resolution last tier applies the target ceiling too.
        val streams = listOf(stream("Show S01E01", "\uD83D\uDC64 300 \uD83D\uDCBE 30 GB"))
        assertNull(AdaptiveStreamPicker.pickBestSeeded(streams, "1080p"))
    }

    @Test
    fun pickBestSeeded_oversizeTierFallsThroughToNextTier() {
        // The 720p tier's only stream is oversize for 720p; the pick moves
        // on to the next tier instead of taking it.
        val streams = listOf(
            stream("Big.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 3.0 GB"),
            stream("Small.2160p", "\uD83D\uDC64 10 \uD83D\uDCBE 8.0 GB"),
        )
        assertEquals("Small.2160p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }

    @Test
    fun pickBestSeeded_oversizeOnlyDubStillWinsOverSaneSub() {
        // The only English dub is a 30 GB remux, far over the 1080p sane
        // ceiling, and the Japanese stream is sane. The user's rule — "if
        // dub is there they play it always if not then sub" — overrides the
        // ceiling for the English pool: the oversize dub still wins instead
        // of falling through to the sane sub. (This replaces the former
        // pickBestSeeded_oversizeOnlyDubFallsBackToSaneSub contract; the
        // hard ceiling still applies to the non-English pool — see
        // pickBestSeeded_oversizeOnlyPoolReturnsNull.)
        val streams = listOf(
            stream("EN.Dual.Audio.1080p", "\uD83D\uDC64 100 \uD83D\uDCBE 30 GB"),
            stream("JP.1080p", "\uD83D\uDC64 50 \uD83D\uDCBE 2.0 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true,
        )
        assertEquals("EN.Dual.Audio.1080p", picked?.name)
    }

    @Test
    fun pickBestSeeded_saneDubBeatsOversizeDub() {
        // The oversize fallback fires only when NO sane dub exists at any
        // quality: a sane 720p dub beats an oversize 1080p dub (and both
        // beat the sane Japanese stream).
        val streams = listOf(
            stream("EN.Dual.Audio.1080p", "\uD83D\uDC64 900 \uD83D\uDCBE 30 GB"),
            stream("EN.Dual.Audio.720p", "\uD83D\uDC64 10 \uD83D\uDCBE 1.5 GB"),
            stream("JP.1080p", "\uD83D\uDC64 500 \uD83D\uDCBE 2.0 GB"),
        )
        val picked = AdaptiveStreamPicker.pickBestSeeded(
            streams, "1080p", preferEnglishAudio = true,
        )
        assertEquals("EN.Dual.Audio.720p", picked?.name)
    }

    @Test
    fun pickBestSeeded_allDubsOversizePicksLeastOversizeThenBestSeeded() {
        // Fixtures must be genuinely English-classified: a bare "EN."
        // prefix is never positive evidence on its own, so each dub name
        // carries a "Dual.Audio" tag (the pattern the other oversize
        // fixtures in this class use). The 1080p sane ceiling is
        // 5,000 MiB = 5,242,880,000 bytes.
        //
        // Least-excess half: both dubs are oversize (30 GB excess
        // 26,969,374,720 bytes; 10 GB excess 5,494,538,240 bytes), so the
        // least-oversize dub wins even though the bigger remux is far
        // better seeded and the Japanese stream is sane.
        val leastExcess = listOf(
            stream("EN.Dual.Audio.Remux.1080p", "\uD83D\uDC64 900 \uD83D\uDCBE 30 GB"),
            stream("EN.Dual.Audio.Encode.1080p", "\uD83D\uDC64 10 \uD83D\uDCBE 10 GB"),
            stream("JP.1080p", "\uD83D\uDC64 500 \uD83D\uDCBE 2.0 GB"),
        )
        assertEquals(
            "EN.Dual.Audio.Encode.1080p",
            AdaptiveStreamPicker.pickBestSeeded(
                leastExcess, "1080p", preferEnglishAudio = true,
            )?.name,
        )
        // Seeder tie-break half: both dubs are the same size (20 GB,
        // excess 16,231,956,480 bytes each), so the excess ties and the
        // better-seeded dub must win — the low-seeded one is listed first,
        // so addon order alone would pick the wrong stream.
        val equalExcess = listOf(
            stream("EN.Dual.Audio.LowSeed.1080p", "\uD83D\uDC64 100 \uD83D\uDCBE 20 GB"),
            stream("EN.Dual.Audio.HighSeed.1080p", "\uD83D\uDC64 800 \uD83D\uDCBE 20 GB"),
            stream("JP.1080p", "\uD83D\uDC64 500 \uD83D\uDCBE 2.0 GB"),
        )
        assertEquals(
            "EN.Dual.Audio.HighSeed.1080p",
            AdaptiveStreamPicker.pickBestSeeded(
                equalExcess, "1080p", preferEnglishAudio = true,
            )?.name,
        )
    }

    // pickForQualitySwitch (audit playback finding 3)

    @Test
    fun pickForQualitySwitch_seedersBeatAddonOrder() {
        // Previously the quality switcher took the first stream at the
        // resolution in addon order; seeders must decide within a class.
        val first = stream("First.720p", "\uD83D\uDC64 5 \uD83D\uDCBE 1.5 GB")
            .copy(url = "https://example.com/first.mp4")
        val seeded = stream("Seeded.720p", "\uD83D\uDC64 400 \uD83D\uDCBE 1.4 GB")
            .copy(url = "https://example.com/seeded.mp4")
        val picked = AdaptiveStreamPicker.pickForQualitySwitch(
            listOf(first, seeded), "720p",
        )
        assertEquals("Seeded.720p", picked?.name)
    }

    @Test
    fun pickForQualitySwitch_keepsCurrentTransportAheadOfSeeders() {
        // Deliberate existing behaviour: a torrent watcher tapping a
        // quality chip stays on torrents even when a direct stream at the
        // same quality is far better seeded.
        val direct = stream("Direct.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 1.4 GB")
            .copy(url = "https://example.com/direct.mp4")
        val torrent = stream("Torrent.720p", "\uD83D\uDC64 5 \uD83D\uDCBE 1.5 GB")
            .copy(infoHash = "0123456789abcdef0123456789abcdef01234567")
        val picked = AdaptiveStreamPicker.pickForQualitySwitch(
            listOf(direct, torrent), "720p", preferTorrent = true,
        )
        assertEquals("Torrent.720p", picked?.name)
    }

    @Test
    fun pickForQualitySwitch_englishDubBeatsBetterSeededNonDub() {
        val japanese = stream("JP.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 1.4 GB")
            .copy(url = "https://example.com/jp.mp4")
        val dub = stream("EN.Dual.Audio.720p", "\uD83D\uDC64 50 \uD83D\uDCBE 1.5 GB")
            .copy(url = "https://example.com/en.mp4")
        val picked = AdaptiveStreamPicker.pickForQualitySwitch(
            listOf(japanese, dub), "720p", preferEnglishAudio = true,
        )
        assertEquals("EN.Dual.Audio.720p", picked?.name)
    }

    @Test
    fun pickForQualitySwitch_readyTorrentBeatsUnresolvedDebridCandidate() {
        // Both are torrent-transport; the plain torrent can start now,
        // the installed-addon debrid candidate still needs its async
        // resolve, so it ranks below despite far more seeders.
        val ready = stream("Ready.720p", "\uD83D\uDC64 10 \uD83D\uDCBE 1.5 GB")
            .copy(infoHash = "0123456789abcdef0123456789abcdef01234567")
        val candidate = stream("Candidate.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 1.4 GB")
            .copy(
                infoHash = "89abcdef0123456789abcdef0123456789abcdef",
                addonId = "addon:debridio",
            )
        val picked = AdaptiveStreamPicker.pickForQualitySwitch(
            listOf(candidate, ready), "720p", preferTorrent = true,
        )
        assertEquals("Ready.720p", picked?.name)
    }

    @Test
    fun pickForQualitySwitch_knownOversizeRanksBelowSane() {
        val oversize = stream("Oversize.720p", "\uD83D\uDC64 900 \uD83D\uDCBE 3.0 GB")
            .copy(url = "https://example.com/oversize.mp4")
        val sane = stream("Sane.720p", "\uD83D\uDC64 50 \uD83D\uDCBE 1.5 GB")
            .copy(url = "https://example.com/sane.mp4")
        val picked = AdaptiveStreamPicker.pickForQualitySwitch(
            listOf(oversize, sane), "720p",
        )
        assertEquals("Sane.720p", picked?.name)
    }

    @Test
    fun pickForQualitySwitch_emptyPoolReturnsNull() {
        assertNull(AdaptiveStreamPicker.pickForQualitySwitch(emptyList(), "720p"))
    }

    @Test
    fun pickBestSeeded_unknownSizesStayPickable() {
        // Streams whose size is unknown remain eligible under the hard ceiling.
        val streams = listOf(
            stream("A.1080p", "\uD83D\uDC64 10"),
            stream("B.1080p", "\uD83D\uDC64 300"),
        )
        assertEquals("B.1080p", AdaptiveStreamPicker.pickBestSeeded(streams, "1080p")?.name)
    }
}
