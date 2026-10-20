package com.nuvio.app.features.streams

private val resolutionPatterns = listOf(
    "2160p" to Regex("\\b(2160p|4k|uhd)\\b"),
    "1440p" to Regex("\\b1440p\\b"),
    "1080p" to Regex("\\b1080p\\b"),
    "720p" to Regex("\\b720p\\b"),
    "576p" to Regex("\\b576p\\b"),
    "480p" to Regex("\\b480p\\b"),
)

/** Resolution label parsed from the stream's name/title/description/filename, or null if unknown. */
internal fun StreamItem.streamResolution(): String? {
    val text = listOfNotNull(name, title, description, behaviorHints.filename).joinToString(" ").lowercase()
    return resolutionPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }?.first
}

/** Quality rank from lowest to highest; used for "closest quality" fallback. */
private val qualityRank = listOf("480p", "576p", "720p", "1080p", "1440p", "2160p")

/**
 * Sane per-episode size ceiling per quality. Streams larger than this are treated as
 * outliers (e.g. remuxes) and are only picked when nothing reasonable exists.
 */
private val saneSizeBytesByQuality = mapOf(
    "480p" to 900L * 1024 * 1024,
    "576p" to 1_100L * 1024 * 1024,
    "720p" to 2_200L * 1024 * 1024,
    "1080p" to 5_000L * 1024 * 1024,
    "1440p" to 9_000L * 1024 * 1024,
    "2160p" to 18_000L * 1024 * 1024,
)

private val seedersPattern = Regex("[\uD83D\uDC64\uD83D\uDC65]\\s*(\\d[\\d,.]*)|\\b(\\d[\\d,.]*)\\s*seeders?\\b", RegexOption.IGNORE_CASE)
private val sizePattern = Regex("[\uD83D\uDCBE]\\s*([\\d.,]+)\\s*(GB|MB)", RegexOption.IGNORE_CASE)

// Dubbed / dual-audio / multi-audio / english-dub / english-audio indicators
// (word-boundaried). Scanned against the UNSTRIPPED name/title/filename: a
// subtitle tag sitting next to a dub tag ("[English Dub][Multi Subs]") must
// not hide it. Description prose ("this release has multi audio options")
// must not flag a release as English-dubbed by itself, so the description is
// never scanned here.
private val strongEnglishDubIndicators = Regex(
    "\\bdubbed\\b|\\bdual[\\s\\-]?audio\\b|\\bmulti[\\s\\-]?audio\\b|\\benglish[\\s\\-]?dub\\b" +
        "|\\benglish[\\s\\-]?audio\\b|\\ben[\\s\\-]?audio\\b",
    RegexOption.IGNORE_CASE,
)
// Standalone "dual"/"multi" are unambiguous multi-audio markers in release names
// (e.g. "Re.Zero.S2.MULTi.1080p"), but can false-positive in description prose
// ("dual timelines"), so they are only matched against name/title/filename,
// with "Multi-Subs"-style subtitle tags stripped first (see multiSubtitleTag).
private val standaloneMultiAudioIndicators = Regex(
    "\\bdual\\b|\\bmulti\\b",
    RegexOption.IGNORE_CASE,
)
// Standalone "dub" tag (e.g. "[DUB]" in Crunchyroll-style rips).
// Name/title/filename only, like the other standalone indicators.
private val standaloneDubIndicator = Regex(
    "\\bdub\\b",
    RegexOption.IGNORE_CASE,
)
// Combined language tags where "eng" is joined to another language code by a
// separator instead of a word boundary ("JPNxENG", "ENGxJPN", "JPN/ENG",
// "ENG+JPN", "JPN.ENG"): a plain \beng\b misses the x-joined forms because
// "x" is a word character. The other side must be a bare 3-letter code, so
// unrelated words containing "eng" cannot match — and it is never "sub":
// "ENG.SUB" / "SUBxENG"-style tags describe subtitle tracks, not audio
// (engSubtitleTag below removes the dotted/spaced forms before this check;
// the (?!sub) guards cover the separator-joined ones).
private val comboLanguageTag = Regex(
    "\\beng\\s*[x/+]\\s*(?!sub)[a-z]{3}\\b|\\b(?!sub)[a-z]{3}\\s*[x/+]\\s*eng\\b" +
        "|\\beng\\s*\\.\\s*(?!sub)[a-z]{3}\\b|\\b(?!sub)[a-z]{3}\\s*\\.\\s*eng\\b",
    RegexOption.IGNORE_CASE,
)
// Standalone "eng" language tag ...
private val engAudioTag = Regex("\\beng\\b", RegexOption.IGNORE_CASE)
// Subtitle mentions, stripped from the full text before the standalone "eng"
// fallback check so subtitle metadata is never read as an audio signal: an
// "eng" tag next to a subtitle word ("english subtitles", "subs: eng"),
// subtitle tags carrying multi/multiple ("Multi-Subs", "Multi Subs",
// "Multiple Subtitle" — Japanese releases with many subtitle tracks, NOT
// multi-audio), and bare subtitle tokens. The eng-near-subs alternatives come
// first so "subs: eng" is removed as one span instead of leaving a bare "eng"
// behind. The span alternatives (.{0,16}?) can swallow words sitting between
// the eng and subtitle tokens, which is why this strip feeds ONLY the "eng"
// fallback — the other checks run on unstripped text or on the narrow token
// strips below.
private val subtitleMention = Regex(
    "\\beng(lish)?\\b.{0,16}?\\bsubs?(titles?)?\\b|\\bsubs?(titles?)?\\b.{0,16}?\\beng(lish)?\\b" +
        "|\\b(multi|multiple)[\\s\\-]?subs?(titles?)?\\b|\\bsubs?(titles?)?\\b",
    RegexOption.IGNORE_CASE,
)
// "Multi-Subs" / "Multi Subs" / "Multiple Subtitle" tags only (subtitleMention's
// third alternative): subtitle-track counts on usually-Japanese releases, NOT
// multi-audio. This token strip is all the standalone dual/multi check needs —
// unlike the span strip above it removes just the tag tokens, so it can never
// swallow a neighbouring dub signal ("[Dual Audio][Multi Subs]").
private val multiSubtitleTag = Regex(
    "\\b(multi|multiple)[\\s\\-]?subs?(titles?)?\\b",
    RegexOption.IGNORE_CASE,
)
// An "eng" token that directly carries a subtitle tag ("ENG.SUB", "ENG Sub"),
// the tail of "JPN.ENG.SUB" included: that "eng" marks English subtitles, not
// audio. Removed before the combined-language-tag check so "sub" is never
// read as the combo partner. Only separators may sit between "eng" and the
// subtitle token, so a real dub tag next to it is never eaten.
private val engSubtitleTag = Regex(
    "\\beng\\b[\\s.\\-+/]*subs?(titles?)?\\b",
    RegexOption.IGNORE_CASE,
)

// HDR indicators: "hdr" covers HDR10/HDR10+; the trailing boundary keeps
// "HDRip" (an SDR rip tag) from matching; "dv" needs word boundaries so it
// doesn't match inside other words (e.g. "DVDRip").
private val hdrIndicators = Regex(
    "\\bhdr(\\d*\\+?)?\\b|\\bdolby[\\s\\-]?vision\\b|\\bdovi\\b|\\bdv\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * Adaptive stream selection: picks a target quality from the device/network state,
 * then the best-seeded sane stream at that quality (falling back to the closest
 * available quality).
 *
 * Pure logic — unit-tested in commonTest.
 */
object AdaptiveStreamPicker {

    /**
     * Target playback quality label.
     *
     * - Battery at 24% or below overrides everything → 480p.
     * - Data saver active (or cellular without an explicit override) → 480p.
     * - Otherwise → 1080p.
     *
     * Unknown battery (-1) is treated as not-low.
     */
    fun targetQuality(
        isCellular: Boolean,
        batteryPercent: Int,
        dataSaverActive: Boolean,
    ): String {
        if (batteryPercent in 0..24) return "480p"
        if (dataSaverActive || isCellular) return "480p"
        return "1080p"
    }

    /** Seeders parsed from the stream description ("👤 500", "500 seeders"), or null if unknown. */
    fun parseSeeders(stream: StreamItem): Int? {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        val match = seedersPattern.find(text) ?: return null
        val raw = match.groups[1]?.value ?: match.groups[2]?.value ?: return null
        return raw.replace(",", "").replace(".", "").toIntOrNull()
    }

    /** Minimum known seeder count for an HDR stream to be auto-play eligible. */
    const val MIN_SEEDERS_FOR_HDR_AUTOPLAY = 50

    /**
     * Whether the English-dub-first ranking applies: follows the user's preferred
     * audio language setting (default "en"). Any English regional variant counts
     * ("en", "en-US", "en-GB", ...): the primary language subtag decides, so a
     * synced "en-us" no longer disables dub-first ranking. The explicit
     * non-language choices ("device", "original", "default") stay disabled.
     */
    fun shouldPreferEnglishAudio(preferredAudioLanguage: String): Boolean =
        preferredAudioLanguage.trim().lowercase()
            .substringBefore('-')
            .substringBefore('_') == "en"

    /**
     * True when the stream's metadata indicates English-dubbed/multi-audio.
     * Subtitle metadata does NOT count: "English subtitles" / "eng subs" and
     * subtitle tags like "Multi-Subs" never flag a release by themselves.
     *
     * Which text each check sees matters. The strong indicators, the
     * standalone "dub" tag and the combined language tags run against the
     * UNSTRIPPED name/title/filename (filename included: addons often put
     * the release tags only in behaviorHints.filename): the span-based
     * subtitle strip below can start at an "eng"/"english" token and
     * swallow a dub tag sitting in front of a subtitle tag
     * ("[English Dub][Multi Subs]"). Only the two
     * checks that genuinely need stripped text consume it — the standalone
     * dual/multi check (the actual "Multi-Subs" false-positive source),
     * stripped of just the multi-subtitle tag tokens, never a span, so a bare
     * "Dual" next to a subtitle tag still counts; and the "eng"-tag fallback
     * (the strip's original use), run on the full text. The description
     * contributes via that "eng" check only, never via strong/standalone
     * prose matches.
     */
    fun hasEnglishAudio(stream: StreamItem): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename)
            .joinToString(" ")
        if (text.isBlank()) return false
        val releaseText = listOfNotNull(stream.name, stream.title, stream.behaviorHints.filename)
            .joinToString(" ")
        if (strongEnglishDubIndicators.containsMatchIn(releaseText)) return true
        if (standaloneDubIndicator.containsMatchIn(releaseText)) return true
        // Combined language tags: first drop "eng" tokens that directly carry
        // a subtitle tag ("ENG.SUB", the tail of "JPN.ENG.SUB") so "sub" is
        // never read as the combo partner; the pattern itself also refuses a
        // "sub" partner for the separator-joined forms ("ENGxSUB").
        val comboText = engSubtitleTag.replace(releaseText, " ")
        if (comboLanguageTag.containsMatchIn(comboText)) return true
        val releaseNoMultiSubs = multiSubtitleTag.replace(releaseText, " ")
        if (standaloneMultiAudioIndicators.containsMatchIn(releaseNoMultiSubs)) return true
        // A standalone "eng" language tag counts, unless it's part of a subtitle mention.
        val audioOnly = subtitleMention.replace(text, " ")
        return engAudioTag.containsMatchIn(audioOnly)
    }

    /** True when the stream's label/title indicates HDR (HDR10/+, Dolby Vision / DoVi / DV). */
    fun isHdrStream(stream: StreamItem): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        return text.isNotBlank() && hdrIndicators.containsMatchIn(text)
    }

    /** Size in bytes parsed from the stream description ("💾 18.54 GB"), or null if unknown. */
    fun parseSizeBytes(stream: StreamItem): Long? {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        val match = sizePattern.find(text) ?: return null
        val amount = match.groups[1]?.value?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val multiplier = if (match.groups[2]?.value.equals("GB", ignoreCase = true)) {
            1024L * 1024 * 1024
        } else {
            1024L * 1024
        }
        return (amount * multiplier).toLong()
    }

    /**
     * Best stream for auto-play. Ranking: English-audio first (dub over sub, always,
     * regardless of quality tier — when [preferEnglishAudio]), then closest quality
     * to [targetQuality] (preferring the lower one on ties), then best-seeded
     * within the sane size band. Streams with an unknown size are allowed but rank
     * below known sane-sized ones. Unknown seeder counts rank below known ones.
     * Ties keep the addon's own ordering.
     *
     * When [requireHdrSeeders], HDR streams need at least
     * [MIN_SEEDERS_FOR_HDR_AUTOPLAY] known seeders to be eligible (playback needs
     * the speed); if the gate leaves zero candidates it falls back to the ungated
     * list so we never pick nothing when streams exist. English-audio streams are
     * exempt from the HDR seeder gate when [preferEnglishAudio] — dub preference
     * outranks the seeder requirement.
     *
     * Returns null when [streams] is empty.
     */
    fun pickBestSeeded(
        streams: List<StreamItem>,
        targetQuality: String,
        preferEnglishAudio: Boolean = false,
        requireHdrSeeders: Boolean = false,
    ): StreamItem? {
        if (streams.isEmpty()) return null
        if (preferEnglishAudio) {
            // Dub over sub, always: the English-audio filter runs BEFORE the HDR
            // seeder gate, so an English dub is never gated out in favor of a
            // non-English stream. English-dub streams are exempt from the HDR
            // seeder requirement (the recursive call is ungated).
            pickBestSeeded(streams.filter { hasEnglishAudio(it) }, targetQuality)?.let { return it }
        }
        val eligible = if (requireHdrSeeders) {
            streams.filter { stream ->
                !isHdrStream(stream) || (parseSeeders(stream) ?: -1) >= MIN_SEEDERS_FOR_HDR_AUTOPLAY
            }.ifEmpty { streams }
        } else {
            streams
        }
        val byQuality = eligible.groupBy { it.streamResolution() }
        val orderedQualities = qualityRank
            .sortedWith(compareBy({ kotlin.math.abs(qualityRank.indexOf(it) - qualityRank.indexOf(targetQuality)) }, { qualityRank.indexOf(it) }))
            .filter { it in byQuality }
        val fallbackOrder = (listOf(targetQuality) + orderedQualities).distinct()
        for (quality in fallbackOrder) {
            val pool = byQuality[quality] ?: continue
            pickFromPool(pool, quality)?.let { return it }
        }
        return null
    }

    private fun pickFromPool(pool: List<StreamItem>, quality: String): StreamItem? {
        if (pool.isEmpty()) return null
        val saneCeiling = saneSizeBytesByQuality[quality] ?: Long.MAX_VALUE
        val sane = pool.filter { stream ->
            val size = parseSizeBytes(stream)
            size == null || size <= saneCeiling
        }
        // Prefer known-size sane streams; unknown sizes are allowed but rank lower.
        val ordered = (sane.filter { parseSizeBytes(it) != null } + sane.filter { parseSizeBytes(it) == null })
            .ifEmpty { pool }
        // maxByOrNull keeps the first of equal scores, so the addon's ordering breaks ties.
        return ordered.maxByOrNull { parseSeeders(it) ?: -1 }
    }
}
