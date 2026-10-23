package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.streamResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeasonDownloadMatchingTest {
    private val packHash = "a".repeat(40)

    @Test
    fun seasonPackTorrentIsReusedForOtherEpisodes() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash, fileIdx = 0)
        val other = torrent("Torrentio\n1080p", "Show.S01E02.1080p.WEB.x264-OTHER", "b".repeat(40))
        val pack = torrent("Torrentio\n1080p", "Show.S01.1080p.WEB.x265-GRP", packHash, fileIdx = 1)

        assertEquals(pack, pickMatchingEpisodeStream(template, listOf(other, pack)))
    }

    @Test
    fun closestReleaseOfTheSameResolutionWins() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash)
        val wrongResolution = torrent("Torrentio\n2160p", "Show.S01E05.2160p.WEB.x265-GRP", "c".repeat(40))
        val otherGroup = torrent("Torrentio\n1080p", "Show.S01E05.1080p.BluRay.x264-OTHER", "d".repeat(40))
        val sameGroup = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x265-GRP", "e".repeat(40))

        assertEquals(sameGroup, pickMatchingEpisodeStream(template, listOf(wrongResolution, otherGroup, sameGroup)))
    }

    @Test
    fun noStreamAtTheTemplateResolutionFallsBackToTheClosestQuality() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB-GRP", packHash)
        val only4k = torrent("Torrentio\n4k", "Show.S01E05.2160p.WEB-GRP", "c".repeat(40))

        assertEquals(only4k, pickMatchingEpisodeStream(template, listOf(only4k)))
    }

    @Test
    fun onlyAHigherQualityAvailableIsPicked() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val only1080 = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x265-GRP", "c".repeat(40))

        assertEquals(only1080, pickMatchingEpisodeStream(template, listOf(only1080)))
    }

    @Test
    fun onlyALowerQualityAvailableIsPicked() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val only480 = torrent("Torrentio\n480p", "Show.S01E05.480p.WEB.x264-GRP", "c".repeat(40))

        assertEquals(only480, pickMatchingEpisodeStream(template, listOf(only480)))
    }

    @Test
    fun equidistantTiersPreferTheOneAtOrBelowTheTemplate() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash)
        val below720 = torrent("Torrentio\n720p", "Show.S01E05.720p.WEB.x265-GRP", "c".repeat(40))
        val above1440 = torrent("Torrentio\n1440p", "Show.S01E05.1440p.WEB.x265-GRP", "d".repeat(40))

        // Both are one tier away from 1080p; the at-or-below tier wins.
        assertEquals(below720, pickMatchingEpisodeStream(template, listOf(above1440, below720)))
    }

    @Test
    fun exactTierStillWinsOverACloserNamedStreamAtAnotherTier() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val exactOtherGroup = torrent("Torrentio\n720p", "Show.S01E05.720p.BluRay.x264-OTHER", "c".repeat(40))
        val fallbackSameGroup = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x265-GRP", "d".repeat(40))

        assertEquals(exactOtherGroup, pickMatchingEpisodeStream(template, listOf(fallbackSameGroup, exactOtherGroup)))
    }

    @Test
    fun unparseableResolutionIsPickedOnlyAsALastResort() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val unparseable = torrent("Torrentio", "Show.S01E05.WEB.x265-GRP", "c".repeat(40))
        val only480 = torrent("Torrentio\n480p", "Show.S01E05.480p.WEB.x264-OTHER", "d".repeat(40))

        // Alone, the unparseable stream is better than skipping the episode.
        assertEquals(unparseable, pickMatchingEpisodeStream(template, listOf(unparseable)))
        // But any parseable quality, however far from the template, beats it.
        assertEquals(only480, pickMatchingEpisodeStream(template, listOf(unparseable, only480)))
    }

    @Test
    fun noUsableCandidatesStillMeansNoMatch() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val httpOnly = http("Addon\n720p", "Show.S01E05.720p.WEB.x265-GRP")

        assertNull(pickMatchingEpisodeStream(template, emptyList()))
        // The closest-quality fallback must not leak across kinds.
        assertNull(pickMatchingEpisodeStream(template, listOf(httpOnly)))
    }

    @Test
    fun resolutionIsReadFromNamesAndTitles() {
        assertEquals("2160p", torrent("Torrentio\n4k", "x", packHash).streamResolution())
        assertEquals("720p", torrent("Addon", "Show 720p WEB", packHash).streamResolution())
        assertEquals("360p", torrent("Torrentio\n360p", "x", packHash).streamResolution())
        assertNull(torrent("Addon", "Show WEB", packHash).streamResolution())
    }

    @Test
    fun wellSeededReleaseBeatsCloserNamedDeadRelease() {
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash)
        val deadExact = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x265-GRP \uD83D\uDC64 0", "b".repeat(40))
        val seededOther = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x264-OTHER \uD83D\uDC64 800", "c".repeat(40))

        // Seeders are the PRIMARY key in the non-dub ranking: the healthy
        // release wins even though the dead one's name matches the template
        // exactly — release-name similarity is now only the tiebreak.
        assertEquals(seededOther, pickMatchingEpisodeStream(template, listOf(deadExact, seededOther)))
    }

    @Test
    fun deadDubAtTemplateResolutionLosesToSeededDubAtAnotherResolution() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val deadDub720 = torrent("Torrentio\n720p", "Show S01E05 720p WEB Dual Audio x265-GRP \uD83D\uDC64 0", "b".repeat(40))
        val seededDub1080 = torrent("Torrentio\n1080p", "Show S01E05 1080p WEB Dual Audio x265-OTHER \uD83D\uDC64 2000", "c".repeat(40))

        // Among dubs, seed health outranks the exact-resolution match: the
        // 0-seeder dub at the template's resolution would just stall.
        assertEquals(
            seededDub1080,
            pickMatchingEpisodeStream(template, listOf(deadDub720, seededDub1080), preferEnglishAudio = true),
        )
    }

    @Test
    fun oversizeReleaseIsDeprioritizedInMatching() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val oversize = torrent("Torrentio\n720p", "Show.S01E05.720p.WEB.x265-GRP \uD83D\uDCBE 30 GB \uD83D\uDC64 900", "b".repeat(40))
        val sane = torrent("Torrentio\n720p", "Show.S01E05.720p.WEB.x264-OTHER \uD83D\uDCBE 1.5 GB \uD83D\uDC64 10", "c".repeat(40))

        // 30 GB is past the 720p sane ceiling: the oversize release only
        // competes when the whole pool is oversize — seeders cannot save it
        // while a sane candidate exists.
        assertEquals(sane, pickMatchingEpisodeStream(template, listOf(oversize, sane)))
    }

    @Test
    fun englishDubAtAnotherResolutionBeatsSameResolutionSub() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val sameResSub = torrent("Torrentio\n720p", "Show.S01E05.720p.WEB.x265-GRP", "f".repeat(40))
        val dub1080 = torrent("Torrentio\n1080p", "Show S01E05 1080p WEB Dual Audio x265-OTHER", "0".repeat(40))

        // Dub over sub, always: the 1080p dub wins over the same-resolution sub.
        assertEquals(
            dub1080,
            pickMatchingEpisodeStream(template, listOf(sameResSub, dub1080), preferEnglishAudio = true),
        )
        // Without the preference, the same-resolution release still wins.
        assertEquals(
            sameResSub,
            pickMatchingEpisodeStream(template, listOf(sameResSub, dub1080), preferEnglishAudio = false),
        )
        assertEquals(sameResSub, pickMatchingEpisodeStream(template, listOf(sameResSub, dub1080)))
    }

    @Test
    fun nonEnglishSeasonPackDoesNotBeatAPerEpisodeDub() {
        // The user picked an episode of a Japanese season pack; another episode
        // also has its own English-dub single. Dub over sub, always: the dub
        // single wins even though the pack's hash matches the template.
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash, fileIdx = 0)
        val pack = torrent("Torrentio\n1080p", "Show.S01.1080p.WEB.x265-GRP", packHash, fileIdx = 4)
        val dubSingle = torrent("Torrentio\n720p", "Show S01E05 720p WEB Dual Audio x265-OTHER", "3".repeat(40))

        assertEquals(
            dubSingle,
            pickMatchingEpisodeStream(template, listOf(pack, dubSingle), preferEnglishAudio = true),
        )
        // Without the preference, the pack shortcut still wins, as before.
        assertEquals(
            pack,
            pickMatchingEpisodeStream(template, listOf(pack, dubSingle), preferEnglishAudio = false),
        )
        // And an English pack is itself the dub, so the shortcut still applies.
        val englishPackTemplate = torrent("Torrentio\n1080p", "Show S01E01 1080p WEB Dual Audio x265-GRP", packHash, fileIdx = 0)
        val englishPack = torrent("Torrentio\n1080p", "Show S01 1080p WEB Dual Audio x265-GRP", packHash, fileIdx = 4)
        assertEquals(
            englishPack,
            pickMatchingEpisodeStream(englishPackTemplate, listOf(englishPack, dubSingle), preferEnglishAudio = true),
        )
    }

    @Test
    fun httpDubBeatsTorrentSubWhenTheTemplateIsATorrent() {
        // The dub exists only as a direct HTTP stream while the template (and
        // the same-resolution alternative) are torrents. The English pool is
        // not restricted to the template's kind, so the dub still wins.
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash)
        val torrentSub = torrent("Torrentio\n1080p", "Show.S01E05.1080p.WEB.x265-GRP", "4".repeat(40))
        val httpDub = http("Addon\n720p", "Show S01E05 720p WEB Dual Audio x265-OTHER")

        assertEquals(
            httpDub,
            pickMatchingEpisodeStream(template, listOf(torrentSub, httpDub), preferEnglishAudio = true),
        )
        // Without the preference, kinds still do not mix: the HTTP stream is
        // not a candidate for a torrent template at all.
        assertEquals(
            torrentSub,
            pickMatchingEpisodeStream(template, listOf(torrentSub, httpDub), preferEnglishAudio = false),
        )
    }

    @Test
    fun amongDubsTemplateResolutionIsTheDecidingKey() {
        val template = torrent("Torrentio\n720p", "Show.S01E01.720p.WEB.x265-GRP", packHash)
        val dub720 = torrent("Torrentio\n720p", "Show S01E05 720p WEB Dual Audio x265-OTHER", "1".repeat(40))
        val dub1080 = torrent("Torrentio\n1080p", "Show S01E05 1080p WEB Dual Audio x265-GRP", "2".repeat(40))

        // Both candidates are dubs; the one at the template's resolution wins
        // even though the 1080p release name is closer to the template (GRP tag).
        assertEquals(
            dub720,
            pickMatchingEpisodeStream(template, listOf(dub1080, dub720), preferEnglishAudio = true),
        )
    }

    @Test
    fun dubForTheWrongEpisodeIsDroppedAndUnmarkedDubStaysEligible() {
        // Target is S02E01. The best-seeded dub is explicitly S02E02 — the dub
        // branch ranks by seeders before name similarity, so without the
        // marker check it would win. It must be dropped; the unmarked dub
        // (no explicit marker) stays eligible and wins instead.
        val template = torrent("Torrentio\n1080p", "Show.S02E01.1080p.WEB.x265-GRP", packHash)
        val wrongEpisodeDub = torrent(
            "Torrentio\n1080p",
            "Show S02E02 1080p WEB Dual Audio x265-OTHER 👤 500",
            "5".repeat(40),
        )
        val unmarkedDub = torrent(
            "Torrentio\n1080p",
            "Show 1080p WEB Dual Audio x265-OTHER 👤 10",
            "6".repeat(40),
        )

        assertEquals(
            unmarkedDub,
            pickMatchingEpisodeStream(
                template,
                listOf(wrongEpisodeDub, unmarkedDub),
                preferEnglishAudio = true,
                targetSeason = 2,
                targetEpisode = 1,
            ),
        )
        // Without target coordinates the check is skipped (movie case) and
        // the best-seeded dub wins, as before.
        assertEquals(
            wrongEpisodeDub,
            pickMatchingEpisodeStream(
                template,
                listOf(wrongEpisodeDub, unmarkedDub),
                preferEnglishAudio = true,
            ),
        )
        // A dub explicitly marked for the target episode is kept and beats
        // the unmarked one on seeders.
        val correctDub = torrent(
            "Torrentio\n1080p",
            "Show S02E01 1080p WEB Dual Audio x265-OTHER 👤 100",
            "7".repeat(40),
        )
        assertEquals(
            correctDub,
            pickMatchingEpisodeStream(
                template,
                listOf(wrongEpisodeDub, unmarkedDub, correctDub),
                preferEnglishAudio = true,
                targetSeason = 2,
                targetEpisode = 1,
            ),
        )
        // The NxN marker shape is checked too.
        val crossMarkedWrongDub = torrent(
            "Torrentio\n1080p",
            "Show 2x02 1080p WEB Dual Audio x265-OTHER 👤 900",
            "8".repeat(40),
        )
        assertEquals(
            unmarkedDub,
            pickMatchingEpisodeStream(
                template,
                listOf(crossMarkedWrongDub, unmarkedDub),
                preferEnglishAudio = true,
                targetSeason = 2,
                targetEpisode = 1,
            ),
        )
    }

    @Test
    fun aspectRatioOnlyCandidateInFilenameIsNotDroppedForItsTargetEpisode() {
        // Target is S01E01. The candidate's ONLY NxN-shaped pair lives in
        // behaviorHints.filename and is an aspect ratio (16x9), not a
        // season/episode marker — there is no SxxExx marker anywhere. It must
        // stay eligible: dropping it would empty the pool and mark the
        // episode missing. (Candidate hashes differ from the template's, so
        // the hash-match exemption cannot mask the marker check.)
        val template = torrent("Torrentio\n1080p", "Show.S01E01.1080p.WEB.x265-GRP", packHash)
        val aspect16x9 = torrent(
            "Torrentio\n1080p",
            "Show Pilot 1080p WEB Dual Audio x265-OTHER",
            "9".repeat(40),
            filename = "Show.Pilot.1080p.WEB.16x9.Dual.Audio.mkv",
        )
        assertEquals(
            aspect16x9,
            pickMatchingEpisodeStream(template, listOf(aspect16x9), targetSeason = 1, targetEpisode = 1),
        )
        val aspect4x3 = torrent(
            "Torrentio\n1080p",
            "Show Pilot 1080p WEB Dual Audio x265-OTHER",
            "8".repeat(40),
            filename = "Show.Pilot.1080p.WEB.4x3.Dual.Audio.mkv",
        )
        assertEquals(
            aspect4x3,
            pickMatchingEpisodeStream(template, listOf(aspect4x3), targetSeason = 1, targetEpisode = 1),
        )
        val aspect21x9 = torrent(
            "Torrentio\n1080p",
            "Show Pilot 1080p WEB Dual Audio x265-OTHER",
            "7".repeat(40),
            filename = "Show.Pilot.1080p.WEB.21x9.Dual.Audio.mkv",
        )
        assertEquals(
            aspect21x9,
            pickMatchingEpisodeStream(template, listOf(aspect21x9), targetSeason = 1, targetEpisode = 1),
        )
        // Filename scanning itself still works: a real SxxExx marker that
        // lives only in the filename and names a different episode drops.
        val wrongEpisodeInFilename = torrent(
            "Torrentio\n1080p",
            "Show 1080p WEB x265-OTHER",
            "6".repeat(40),
            filename = "Show.S01E02.1080p.WEB.x265-OTHER.mkv",
        )
        assertNull(
            pickMatchingEpisodeStream(
                template,
                listOf(wrongEpisodeInFilename),
                targetSeason = 1,
                targetEpisode = 1,
            ),
        )
    }

    @Test
    fun shorthandSeasonRangeIsKeptForEveryEpisodeItCovers() {
        // "S02E01-E02": the bare -E02 endpoint carries no S, so only the
        // range parse keeps the candidate for E02. Target E05 is outside
        // the range and must still be dropped.
        val template = torrent("Torrentio\n1080p", "Show.S02E01.1080p.WEB.x265-GRP", packHash)
        val rangeTo2 = torrent("Torrentio\n1080p", "Show S02E01-E02 1080p WEB x265-OTHER", "9".repeat(40))

        assertEquals(
            rangeTo2,
            pickMatchingEpisodeStream(template, listOf(rangeTo2), targetSeason = 2, targetEpisode = 2),
        )
        assertEquals(
            rangeTo2,
            pickMatchingEpisodeStream(template, listOf(rangeTo2), targetSeason = 2, targetEpisode = 1),
        )
        assertNull(pickMatchingEpisodeStream(template, listOf(rangeTo2), targetSeason = 2, targetEpisode = 5))

        // "S02E01-E03" also covers the interior episode E02, not just the
        // two written endpoints.
        val rangeTo3 = torrent("Torrentio\n1080p", "Show S02E01-E03 1080p WEB x265-OTHER", "8".repeat(40))
        assertEquals(
            rangeTo3,
            pickMatchingEpisodeStream(template, listOf(rangeTo3), targetSeason = 2, targetEpisode = 3),
        )
        assertEquals(
            rangeTo3,
            pickMatchingEpisodeStream(template, listOf(rangeTo3), targetSeason = 2, targetEpisode = 2),
        )
        assertNull(pickMatchingEpisodeStream(template, listOf(rangeTo3), targetSeason = 2, targetEpisode = 5))
    }

    @Test
    fun shorthandCrossRangeIsKeptForEveryEpisodeItCovers() {
        // NxN form of the same shorthand: "2x01-02" ends in a bare number.
        val template = torrent("Torrentio\n1080p", "Show.S02E01.1080p.WEB.x265-GRP", packHash)
        val rangeTo2 = torrent("Torrentio\n1080p", "Show 2x01-02 1080p WEB x265-OTHER", "9".repeat(40))

        assertEquals(
            rangeTo2,
            pickMatchingEpisodeStream(template, listOf(rangeTo2), targetSeason = 2, targetEpisode = 2),
        )
        assertNull(pickMatchingEpisodeStream(template, listOf(rangeTo2), targetSeason = 2, targetEpisode = 5))

        val rangeTo3 = torrent("Torrentio\n1080p", "Show 2x01-03 1080p WEB x265-OTHER", "8".repeat(40))
        assertEquals(
            rangeTo3,
            pickMatchingEpisodeStream(template, listOf(rangeTo3), targetSeason = 2, targetEpisode = 3),
        )
        assertEquals(
            rangeTo3,
            pickMatchingEpisodeStream(template, listOf(rangeTo3), targetSeason = 2, targetEpisode = 2),
        )
    }

    private fun torrent(
        name: String,
        title: String,
        infoHash: String,
        fileIdx: Int? = null,
        filename: String? = null,
    ) = StreamItem(
        name = name,
        title = title,
        infoHash = infoHash,
        fileIdx = fileIdx,
        behaviorHints = StreamBehaviorHints(filename = filename),
        addonName = "Torrentio",
        addonId = "addon:torrentio:https://example.com/manifest.json",
    )

    private fun http(name: String, title: String) = StreamItem(
        name = name,
        title = title,
        url = "https://example.com/video.mkv",
        addonName = "Torrentio",
        addonId = "addon:torrentio:https://example.com/manifest.json",
    )
}
