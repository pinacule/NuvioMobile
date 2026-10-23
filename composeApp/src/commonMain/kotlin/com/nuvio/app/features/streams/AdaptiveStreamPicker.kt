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
 * Sane per-episode size ceiling per quality. For auto-pick, a stream whose
 * KNOWN size exceeds its tier's ceiling is never eligible (e.g. a 30 GB
 * remux at a 1080p target): the ceiling is a hard filter in [pickFromPool],
 * not a preference. Streams with unknown size stay eligible. Downloads do
 * not use this picker and are unaffected. The single exception is
 * [AdaptiveStreamPicker.pickBestSeeded]'s English-oversize fallback: when
 * the user prefers English audio and EVERY English dub is oversize, the
 * least-oversize dub is still picked — dub over sub, always — while the
 * non-English pool keeps the hard filter unchanged.
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

// English-specific audio indicators (word-boundaried): "English Dub",
// "English Audio", "EN.Audio". Scanned against the UNSTRIPPED
// name/title/filename: a subtitle tag sitting next to a dub tag
// ("[English Dub][Multi Subs]") must not hide it. Description prose
// ("this release has multi audio options") must not flag a release as
// English-dubbed by itself, so the description is never scanned here (the
// one description check, descriptionEnglishDubIndicator, requires an
// explicit English dub/audio phrase). The separator class includes "."
// because dot-joined release names write these tags as "English.Dub" /
// "EN.Audio".
private val explicitEnglishAudioIndicators = Regex(
    "\\benglish[\\s\\-.]?dub\\b|\\benglish[\\s\\-.]?audio\\b|\\ben[\\s\\-.]?audio\\b",
    RegexOption.IGNORE_CASE,
)
// Dual/multi-audio indicators: "Dual Audio", "Multi.Audio". These imply an
// English track by release convention, UNLESS the release explicitly lists
// its audio languages and none of them is English (see
// listsOnlyNonEnglishLanguages in hasEnglishAudio).
private val dualMultiAudioIndicators = Regex(
    "\\bdual[\\s\\-.]?audio\\b|\\bmulti[\\s\\-.]?audio\\b",
    RegexOption.IGNORE_CASE,
)
// Standalone "dual"/"multi" are multi-audio markers in release names
// (e.g. "Re.Zero.S2.MULTi.1080p"), but can false-positive in description prose
// ("dual timelines"), so they are only matched against name/title/filename,
// with "Multi-Subs"-style subtitle tags stripped first (see multiSubtitleTag),
// and only in tag position (see hasStandaloneMultiAudioTag).
private val standaloneMultiAudioIndicators = Regex(
    "\\bdual\\b|\\bmulti\\b",
    RegexOption.IGNORE_CASE,
)
// Tokens that legitimately follow a standalone dual/multi TAG: other release
// tags and language codes. An ordinary word instead ("Dual Destinies")
// means the token is part of a show/episode title, not a tag. Resolution
// tokens ("1080p"), years and channel layouts ("5.1") are matched by the
// standaloneTag*Token regexes below.
private val standaloneTagFollowTokens = setOf(
    "audio", "dub", "dubbed", "sub", "subs", "subtitle", "subtitles",
    "english", "eng", "jpn", "kor", "spa", "fre", "ger", "ita", "rus", "por",
    "x264", "x265", "h264", "h265", "hevc", "avc", "web", "webdl", "webrip",
    "bluray", "bdrip", "brrip", "hdtv", "hdrip", "dvdrip", "remux", "proper",
    "repack", "flac", "aac", "ac3", "eac3", "dts", "atmos", "hdr", "hdr10", "hdr10plus", "dv", "ddp", "dd",
    "amzn", "complete", "pack", "batch", "4k", "uhd",
)
// Codec prefixes for the same gate: scene names fuse the codec and the
// channel count into one token ("DUAL.DDP5.1" extracts as "ddp5"), so a
// bare set lookup misses the standard dual-audio naming. A follow token
// counts when it is one of these codecs, optionally plus a run of digits
// (the fused channel integer: "ddp5", "dd5", "ddp51"). Anything past the
// prefix must be digits only, so a title word that merely starts with a
// codec string ("Ddramas") still does not count.
private val standaloneTagCodecPrefixes = listOf(
    "ddp", "eac3", "truehd", "dts", "atmos", "flac", "aac", "ac3", "dd",
)
private val standaloneTagResolutionToken = Regex("\\d{3,4}p")
// A year ("DUAL 2024") or an audio-channel layout ("Dual 5.1", "Dual 7.1")
// following a standalone dual/multi token is also tag position: release
// names park both exactly where a resolution/codec tag would sit.
private val standaloneTagYearToken = Regex("\\d{4}")
private val standaloneTagChannelToken = Regex("\\d+\\.\\d+")
// "Dub"/"dubbed" tags (e.g. "[DUB]" in Crunchyroll-style rips). A bare dub
// tag names no language; by anime-release convention it is the English dub,
// so it still counts when no language is named at all. But when the release
// names a NON-English language and no English marker, the dub is that
// language's ("Hindi Dub", "JPN DUB") — not English.
// Name/title/filename only, like the other standalone indicators.
private val dubIndicators = Regex(
    "\\bdub\\b|\\bdubbed\\b",
    RegexOption.IGNORE_CASE,
)
// A bare "en" code counts as English rescue evidence only in audio-tag
// position — directly next to a dub/audio token ("EN DUB", "Dual Audio
// EN"). Anywhere else it is as likely to be a title word or a foreign
// phrase ("En Espanol" — "in Spanish") as a language code, so on its own
// it must not cancel a non-English veto (see hasEnglishRescueEvidence).
private val enAudioAdjacentTag = Regex(
    "\\ben[\\s\\-.\\[\\]()/:]*\\b(dub|dubbed|audio)\\b" +
        "|\\b(dub|dubbed|audio)[\\s\\-.\\[\\]()/:]*\\ben\\b",
    RegexOption.IGNORE_CASE,
)
// Non-English language markers: full names plus the ISO codes that are
// unambiguous in release names. Codes that collide with common title words
// or names are deliberately omitted ("ben" — Ben 10; "dan" — Dan Da Dan;
// "mal"/"kan"/"tam" — character names; "per"/"mar"/"fin" — English words);
// the full language names still cover those cases. "latino"/"castellano"/
// "espanol" name the Spanish dubs common in Latin-American releases, and
// "pt"/"bul" are standalone tokens guarded by the same word boundaries as
// every other code. French scene releases name their audio with fused
// tokens instead of "french"/"fra": "vff" (version française), "vfq"
// (version française québécoise) and "truefrench". Those tokens are
// fused INTERNALLY but still appear as standalone tokens in release
// names, so the alternation's existing \b...\b wrapping matches them
// whole — no boundary relaxation is needed (dropping it would let them
// match inside unrelated words). Without them a "MULTi VFF" release
// (Japanese + French audio) named no veto language at all and its MULTi
// tag flagged it as an English dub. "vostfr" is deliberately NOT in
// this list: VOSTFR (Version Originale Sous-Titrée FR) names the
// SUBTITLE language — original, unspecified-language audio plus French
// subtitles — not an audio language, so it must not veto neutral
// English evidence the way the genuine French dubbed-audio tokens do.
private const val nonEnglishLanguageAlternation =
    "japanese|jpn|jp|hindi|hin|spanish|spa|french|fra|fre|vff|vfq|truefrench|german|ger|deu" +
        "|italian|ita|portuguese|por|russian|rus|korean|kor|chinese|zho" +
        "|arabic|tamil|telugu|malayalam|kannada|bengali|marathi|punjabi|urdu|urd" +
        "|thai|tha|vietnamese|vie|indonesian|ind|malay|dutch|nld|dut" +
        "|polish|turkish|tur|ukrainian|ukr|czech|ces|cze|slovak|svk" +
        "|hungarian|swedish|swe|norwegian|nor|danish|finnish|greek" +
        "|hebrew|heb|persian|farsi|fas|mandarin|cantonese|tagalog|filipino" +
        "|latino|castellano|espanol|pt|bul"
private val nonEnglishLanguageTag = Regex(
    "\\b($nonEnglishLanguageAlternation)\\b",
    RegexOption.IGNORE_CASE,
)
// A language token bound to a subtitle tag ("[ENG Subs]", "JPN Subs", and
// the same pairing written subs-first): that token describes the subtitle
// tracks, not an audio track, so it must drive NEITHER side of the
// language veto in hasEnglishAudio — an "ENG" from a subs tag must not
// rescue a Japanese dub ("[JPN Dub][ENG Subs]"), and a "JPN" from a subs
// tag must not veto a bare dub ("[DUB] [JPN Subs]"). Between the language
// token and the subtitle token, only separators and further language /
// "multi" tokens may sit: subtitle tags routinely name a whole cluster
// ("[ENG Multi Subs]", "[ENG/JPN Subs]"), and every token of the cluster
// is subtitle-bound. A neighbouring real audio tag is never eaten —
// "dub"/"audio" tokens break the chain, so "[ENG Dub][Multi Subs]" keeps
// its ENG. Consumers: the veto text and the combined-tag text (a
// subtitle-bound "ENG/JPN" cluster must not form a combined audio tag
// either); every other check keeps its own text.
private val subtitleBoundLanguageTag = Regex(
    "\\b(english|eng|en|$nonEnglishLanguageAlternation)\\b" +
        "(?:[\\s.\\-+/,:]+(english|eng|en|$nonEnglishLanguageAlternation|multi|multiple)\\b)*" +
        "[\\s.\\-+/,:]*subs?(titles?)?\\b" +
        "|\\bsubs?(titles?)?\\b" +
        "(?:[\\s.\\-+/,:]+(english|eng|en|$nonEnglishLanguageAlternation|multi|multiple)\\b)*" +
        "[\\s.\\-+/,:]*\\b(english|eng|en|$nonEnglishLanguageAlternation)\\b",
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
// behind. The span alternatives (.{0,32}?) can swallow words sitting between
// the eng and subtitle tokens, which is why this strip feeds ONLY the
// fallback checks (the "eng" tag and the bare-"English" tag) — every other
// check runs on unstripped text or on the narrow token strips below, so a
// widened span can never eat a real dub tag: those checks have already run.
// The span is 32 because release names park resolution/codec tokens between
// the eng tag and a trailing "Subs" ("ENG 1080p WEB-DL x264 Subs" — a
// 19-char gap the old 16-char span missed, leaking the ENG through as an
// audio tag). Lazy and length-bounded, so matching stays linear-ish on
// release-name-sized inputs.
private val subtitleMention = Regex(
    "\\beng(lish)?\\b.{0,32}?\\bsubs?(titles?)?\\b|\\bsubs?(titles?)?\\b.{0,32}?\\beng(lish)?\\b" +
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
// The one description check: an explicit English dub/audio phrase —
// "English Dub | DDP5.1", "Dub: English", "Audio: English". Addons
// sometimes state the language ONLY in the description. The language word
// and the dub/audio word must be adjacent (separators only between them),
// so generic audio prose ("this release has multi audio options") and
// "English subtitles" mentions never match.
private val descriptionEnglishDubIndicator = Regex(
    "\\benglish[\\s\\-:|/]{0,3}(dub|dubbed|audio)\\b|\\b(dub|dubbed|audio)[\\s\\-:|/]{0,3}english\\b" +
        "|\\beng[\\s\\-:|/]{0,3}(dub|dubbed|audio)\\b",
    RegexOption.IGNORE_CASE,
)
// Anchor for the bare-"English" tag check: release tags live at/after the
// episode marker, the year, or a resolution token. An "english" appearing
// before every anchor is part of the show's name ("The.English.Game") and
// must not count as a language tag.
private val releaseTagAnchor = Regex(
    "s\\d{1,2}e\\d{1,2}|\\b\\d{1,2}x\\d{1,2}\\b|\\b(19|20)\\d{2}\\b" +
        "|\\b(2160|1440|1080|720|576|480)p\\b|\\b4k\\b|\\buhd\\b",
    RegexOption.IGNORE_CASE,
)
private val englishWordTag = Regex("\\benglish\\b", RegexOption.IGNORE_CASE)
// Language tokens that name English in structured parsed metadata
// (StreamClientResolveParsed.languages / .audio): the ISO codes and the
// full name, matching how DebridStreamPresentation.languageFor reads the
// same lists (code or label, case-insensitive). Values are tokenized on
// non-letters (see parsedMetadataTokens), so regional forms ("en-US")
// reduce to their primary token before this set is consulted.
private val englishParsedLanguageTokens = setOf("en", "eng", "english")
private val parsedMetadataTokenSeparator = Regex("[^a-z]+")

// HDR indicators: "hdr" covers HDR10/HDR10+; the trailing boundary keeps
// "HDRip" (an SDR rip tag) from matching; "dv" needs word boundaries so it
// doesn't match inside other words (e.g. "DVDRip").
private val hdrIndicators = Regex(
    "\\bhdr(\\d*\\+?)?\\b|\\bdolby[\\s\\-]?vision\\b|\\bdovi\\b|\\bdv\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * Detection-only normalization: "_" is a regex word character, so an
 * underscore-joined filename ("Re_Zero_01_English_Dub_1080p.mkv") hides
 * every word boundary the dub checks rely on and reads as one long word.
 * Treating "_" as a separator makes those tags visible. Applied only to
 * the text fed to the detection checks — never to stored/displayed values.
 */
private fun String.toDetectionText(): String = replace('_', ' ')

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
     * Structured parsed metadata (clientResolve.stream.raw.parsed) is
     * consulted first and settles the question when it names languages —
     * see the parsed check at the top of the function body.
     * Subtitle metadata does NOT count: "English subtitles" / "eng subs" and
     * subtitle tags like "Multi-Subs" never flag a release by themselves.
     *
     * Which text each check sees matters. The explicit indicators, the
     * dual/multi-audio indicators, the "dub" tag and the combined language
     * tags run against the UNSTRIPPED name/title/filename (filename included:
     * addons often put the release tags only in behaviorHints.filename): the
     * span-based subtitle strip below can start at an "eng"/"english" token
     * and swallow a dub tag sitting in front of a subtitle tag
     * ("[English Dub][Multi Subs]"). Only the checks that genuinely need
     * stripped text consume it — the standalone dual/multi check (the actual
     * "Multi-Subs" false-positive source), stripped of just the
     * multi-subtitle tag tokens, never a span, so a bare "Dual" next to a
     * subtitle tag still counts; and the "eng"-tag fallback (the strip's
     * original use), run on the full text. All detection text is normalized
     * (see toDetectionText) so underscore-joined filenames are visible to
     * the checks.
     *
     * Language-neutral tags (dual/multi audio, a bare "dub") imply English
     * only while the release does not explicitly name its audio languages
     * without English: a release that lists languages ("Dual Audio JPN
     * POR", "Hindi Dub", "JPN DUB") and names no English marker is not
     * English audio, however many neutral tags it carries. Languages bound
     * to subtitle tags are not audio listings at all: they are stripped
     * before the veto is computed, so "[JPN Dub][ENG Subs]" is vetoed by
     * the JPN while "[DUB] [JPN Subs]" is not vetoed by the JPN. The
     * English side of the veto is held to the same tag discipline (see
     * hasEnglishRescueEvidence): a show title that happens to contain
     * "English" ("English Teacher") or a foreign phrase ("En Espanol")
     * does not cancel a named non-English dub.
     */
    fun hasEnglishAudio(stream: StreamItem): Boolean {
        // Structured parsed metadata is stronger evidence than title tags:
        // when StreamParser's parsed block (clientResolve.stream.raw.parsed,
        // read the same way in DebridStreamPresentation) names the release's
        // languages, that listing settles the question. English named in
        // parsed.languages or parsed.audio is positive evidence even when
        // no title tag mentions it; a non-empty language listing with no
        // English in either list is a veto — the release's audio is those
        // languages, so a "[DUB]" / "English Dub" title tag cannot make it
        // an English dub. Neutral listings ("multi" / "unknown") name no
        // language and settle nothing. With no parsed data (or no named
        // language in it) the text checks below run exactly as before, so a
        // bare "Dub" with no language named anywhere — parsed fields
        // included — still counts as English.
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        if (parsed != null) {
            val namedParsedLanguages = parsedMetadataTokens(parsed.languages)
                .filter { it != "multi" && it != "multiple" && it != "unknown" }
            val parsedNamesEnglish = namedParsedLanguages.any { it in englishParsedLanguageTokens } ||
                parsedMetadataTokens(parsed.audio).any { it in englishParsedLanguageTokens }
            if (parsedNamesEnglish) return true
            if (namedParsedLanguages.isNotEmpty()) return false
        }
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename)
            .joinToString(" ")
            .toDetectionText()
        if (text.isBlank()) return false
        val releaseFields = listOfNotNull(stream.name, stream.title, stream.behaviorHints.filename)
            .map { it.toDetectionText() }
        val releaseText = releaseFields.joinToString(" ")
        // The veto reads subtitle-stripped text: a language token bound to
        // a subtitle tag ("[ENG Subs]", "[JPN Subs]") describes subtitle
        // tracks, not audio, so it counts for neither side (see
        // subtitleBoundLanguageTag). The strip runs per field so the
        // bare-"English" anchor check below can also work per field: an
        // anchor (episode marker / resolution) in the addon name must not
        // "tag-position" an "English" sitting in the title.
        val releaseAudioFields = releaseFields.map { subtitleBoundLanguageTag.replace(it, " ") }
        val releaseAudioText = releaseAudioFields.joinToString(" ")
        val listsOnlyNonEnglishLanguages = hasNonEnglishAudioLanguage(releaseAudioText) &&
            !hasEnglishRescueEvidence(releaseAudioFields)
        if (explicitEnglishAudioIndicators.containsMatchIn(releaseText)) return true
        if (!listsOnlyNonEnglishLanguages && dualMultiAudioIndicators.containsMatchIn(releaseText)) return true
        if (!listsOnlyNonEnglishLanguages && dubIndicators.containsMatchIn(releaseText)) return true
        // Combined language tags: first drop "eng" tokens that directly carry
        // a subtitle tag ("ENG.SUB", the tail of "JPN.ENG.SUB") so "sub" is
        // never read as the combo partner, then drop whole subtitle-bound
        // language clusters ("ENG/JPN Subs"), whose "ENG/JPN" is a subtitle
        // listing, not a combined audio tag; the pattern itself also refuses
        // a "sub" partner for the separator-joined forms ("ENGxSUB").
        val comboText = subtitleBoundLanguageTag.replace(
            engSubtitleTag.replace(releaseText, " "),
            " ",
        )
        if (comboLanguageTag.containsMatchIn(comboText)) return true
        val releaseNoMultiSubs = multiSubtitleTag.replace(releaseText, " ")
        if (!listsOnlyNonEnglishLanguages && hasStandaloneMultiAudioTag(releaseNoMultiSubs)) {
            return true
        }
        // The description counts only when it states an English dub/audio
        // phrase outright ("English Dub | DDP5.1") — addons sometimes put
        // the language information nowhere else.
        val description = stream.description?.toDetectionText()
        if (!description.isNullOrBlank() &&
            descriptionEnglishDubIndicator.containsMatchIn(description)
        ) {
            return true
        }
        // A bare "English" tag counts in tag position (at/after the
        // episode/year/resolution anchor), with subtitle mentions stripped
        // first so "[English Subtitles]" never counts. The check runs per
        // field: an "English" in the show's name sits before its own
        // field's anchors and is ignored, and an anchor in the addon name
        // ("Torrentio 1080p") must not promote a title's "English" into a
        // tag ("The English Game").
        if (releaseFields.any { field -> hasBareEnglishTag(subtitleMention.replace(field, " ")) }) {
            return true
        }
        // A standalone "eng" language tag counts, unless it's part of a subtitle mention.
        val audioOnly = subtitleMention.replace(text, " ")
        return engAudioTag.containsMatchIn(audioOnly)
    }

    /**
     * Bare "english" word as a release tag ("Re.Zero.S01E01.English.1080p").
     * Only the text at/after the first tag anchor (episode marker, year,
     * resolution) is searched — see releaseTagAnchor. [text] must already
     * have subtitle mentions stripped.
     */
    private fun hasBareEnglishTag(text: String): Boolean {
        val anchor = releaseTagAnchor.find(text) ?: return false
        return englishWordTag.containsMatchIn(text.substring(anchor.range.first))
    }

    /**
     * Tokens of one structured parsed-metadata list: each entry lowercased
     * and split on non-letters, so "en-US" yields "en", "EN.Audio" yields
     * "en"/"audio", and full names ("English") survive as single tokens.
     */
    private fun parsedMetadataTokens(values: List<String>): Set<String> =
        values.flatMap { value -> value.lowercase().split(parsedMetadataTokenSeparator) }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * The non-English side of the language veto, over subtitle-stripped
     * release text. Two alternation codes collide with ordinary release
     * text and are filtered per match: "pt" directly followed by a short
     * number is a part marker ("Pt.2"), not Portuguese; "spa" counts only
     * in audio/tag context, so an episode title like "Spa Day" cannot
     * veto while "[SPA Dub]", "Dual Audio SPA" and "SPA 1080p" still do.
     */
    private fun hasNonEnglishAudioLanguage(releaseAudioText: String): Boolean =
        nonEnglishLanguageTag.findAll(releaseAudioText).any { match ->
            when (match.value.lowercase()) {
                "pt" -> !isPartMarker(releaseAudioText, match)
                "spa" -> hasSpaAudioOrTagContext(releaseAudioText, match)
                else -> true
            }
        }

    /** "Pt" + separators + a 1–2 digit number is a part marker, not a language. */
    private fun isPartMarker(text: String, match: MatchResult): Boolean {
        var i = match.range.last + 1
        while (i < text.length && (text[i] == ' ' || text[i] == '-' || text[i] == '.')) i++
        val start = i
        while (i < text.length && text[i].isDigit()) i++
        return i - start in 1..2
    }

    /**
     * Whether a "spa" token names the Spanish language: followed by an
     * audio token ("SPA Dub"), a release tag, codec or resolution
     * ("SPA 1080p", "SPA x264", "SPA DDP5.1"), preceded by an audio token
     * ("Dual Audio SPA"), sitting at the end of the text, or
     * bracket-wrapped ("[SPA]"). Followed by an ordinary word instead
     * ("Spa Day") it is part of a title.
     */
    private fun hasSpaAudioOrTagContext(text: String, match: MatchResult): Boolean {
        val next = tokenAfter(text, match.range.last + 1)
        if (next.isEmpty()) return true // trailing token: tag position
        val nextToken = next.lowercase()
        if (nextToken in audioContextTokens) return true
        if (nextToken in standaloneTagFollowTokens) return true
        if (nextToken in standaloneTagCodecPrefixes) return true
        // Fused codec + channel tokens ("SPA.DDP5.1" extracts "ddp5") are
        // tag position too, same as in the standalone dual/multi gate.
        if (isCodecFollowToken(nextToken)) return true
        if (standaloneTagResolutionToken.matches(nextToken)) return true
        if (standaloneTagYearToken.matches(nextToken)) return true
        if (tokenBefore(text, match.range.first).lowercase() in audioContextTokens) return true
        var i = match.range.first
        while (i > 0 && text[i - 1] == ' ') i--
        return i > 0 && (text[i - 1] == '[' || text[i - 1] == '(')
    }

    /** The alphanumeric token at/after [from] (tag separators skipped), or "" if none. */
    private fun tokenAfter(text: String, from: Int): String {
        var i = from
        while (i < text.length && text[i] in tagSeparatorChars) i++
        val start = i
        while (i < text.length && text[i].isLetterOrDigit()) i++
        return text.substring(start, i)
    }

    /** The alphanumeric token ending at/before [until] (tag separators skipped), or "" if none. */
    private fun tokenBefore(text: String, until: Int): String {
        var i = until
        while (i > 0 && text[i - 1] in tagSeparatorChars) i--
        val end = i
        while (i > 0 && text[i - 1].isLetterOrDigit()) i--
        return text.substring(i, end)
    }

    private val audioContextTokens = setOf("dub", "dubbed", "audio", "dual", "multi")
    private val tagSeparatorChars =
        setOf(' ', '-', '.', '_', '[', ']', '(', ')', '|', ':', '/', '+')

    /**
     * English evidence strong enough to cancel a non-English audio listing:
     * the "eng" code, a bare "en" directly next to a dub/audio token
     * (enAudioAdjacentTag), or a bare "English" in tag position within its
     * own field (hasBareEnglishTag). Title text alone never rescues: a
     * show named "English Teacher" must not turn a Japanese dub English,
     * and the Spanish phrase "En Espanol" must not pose as an English
     * marker. [audioFields] are the per-field subtitle-stripped release
     * fields — a subtitle-bound "eng" was already removed from them, so it
     * cannot rescue here either.
     */
    private fun hasEnglishRescueEvidence(audioFields: List<String>): Boolean {
        val joined = audioFields.joinToString(" ")
        if (engAudioTag.containsMatchIn(joined)) return true
        if (enAudioAdjacentTag.containsMatchIn(joined)) return true
        return audioFields.any { hasBareEnglishTag(it) }
    }

    /**
     * Standalone "dual"/"multi" in TAG position. As a release tag the token
     * is followed by a separator, another known tag, a resolution, a year,
     * an audio-channel layout, or the end of the text
     * ("Show.S01E01.DUAL.1080p", "MULTi [ENG Subs]").
     * Inside a show/episode title it is followed by an ordinary word
     * ("Dual Destinies") — that must not flag English audio. A trailing
     * ALL-CAPS token counts as a release-group tag ("DUAL-GRP"); a
     * Capitalized one does not ("Dual Destinies" again). [text] must
     * already have multi-subtitle tag tokens stripped.
     */
    private fun hasStandaloneMultiAudioTag(text: String): Boolean {
        for (match in standaloneMultiAudioIndicators.findAll(text)) {
            val rest = text.substring(match.range.last + 1)
                .trimStart(' ', '-', '.', '_', '[', ']', '(', ')', '|', ':', '/', '+')
            if (rest.isEmpty()) return true
            val nextToken = rest.takeWhile { it.isLetterOrDigit() }
            if (nextToken.isEmpty()) return true // another separator/symbol follows
            val token = nextToken.lowercase()
            if (token in standaloneTagFollowTokens) return true
            // Fused codec + channel tokens ("DUAL.DDP5.1" extracts "ddp5").
            if (isCodecFollowToken(token)) return true
            if (token == "h") {
                // "DUAL.H.264": the codec is split as "H" + ".264"/".265".
                val tail = rest.substring(nextToken.length)
                if ((tail.startsWith(".264") || tail.startsWith(".265")) &&
                    tail.getOrNull(4)?.isDigit() != true
                ) {
                    return true
                }
            }
            if (standaloneTagResolutionToken.matches(token)) return true
            if (standaloneTagYearToken.matches(token)) return true
            val channelMatch = standaloneTagChannelToken.find(rest)
            if (channelMatch != null && channelMatch.range.first == 0) return true
            val afterToken = rest.substring(nextToken.length).trim()
            if (afterToken.isEmpty() && nextToken.length in 2..8 &&
                nextToken.all { it.isUpperCase() || it.isDigit() }
            ) {
                return true // trailing release-group style tag, e.g. "DUAL-GRP"
            }
        }
        return false
    }

    /**
     * A follow token that is a codec name, optionally fused with the
     * channel count's digits: "ddp5" (DDP5.1), "dd5" (DD5.1), "ddp51".
     * Anything past the codec prefix must be digits, so title words that
     * merely start with a codec string ("Ddramas") do not count.
     */
    private fun isCodecFollowToken(token: String): Boolean =
        standaloneTagCodecPrefixes.any { prefix ->
            token.startsWith(prefix) && token.drop(prefix.length).all { it.isDigit() }
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
     * Streams with an unparseable resolution form a last tier, tried only after
     * every known-quality pool. Ties keep the addon's own ordering.
     *
     * When [requireHdrSeeders], HDR streams need at least
     * [MIN_SEEDERS_FOR_HDR_AUTOPLAY] known seeders to be eligible (playback needs
     * the speed); if the gate leaves zero candidates it falls back to the ungated
     * list so we never pick nothing when streams exist. English-audio streams are
     * exempt from the HDR seeder gate when [preferEnglishAudio] — dub preference
     * outranks the seeder requirement.
     *
     * Returns null when [streams] is empty, or when every candidate is
     * known to exceed its tier's size ceiling — the auto-pick callers then
     * fall back to the manual source list / next-episode card instead of
     * auto-playing an oversize stream. One exception: when
     * [preferEnglishAudio] and the English pool is non-empty but entirely
     * oversize, its least-oversize member is returned instead (see
     * pickOversizeFallback) — dub over sub, always, per the user's rule.
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
            val english = streams.filter { hasEnglishAudio(it) }
            if (english.isNotEmpty()) {
                pickBestSeeded(english, targetQuality)?.let { return it }
                // The recursion found nothing, which means every English
                // stream is KNOWN to exceed its tier's sane-size ceiling
                // (sane and unknown-size streams are pickable, so one would
                // have been returned — a sane dub at ANY quality beats this
                // fallback, because the recursion walks every tier first).
                // Dub over sub, always: the least-oversize dub still wins
                // over a sane non-English stream instead of falling through
                // to the non-English pool below.
                pickOversizeFallback(english, targetQuality)?.let { return it }
            }
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
        // Last tier: streams whose resolution could not be parsed. They
        // never outrank a parseable candidate, but they stay selectable —
        // e.g. an English dub with no resolution token in its metadata when
        // no parseable English option exists (the English pool recurses
        // through this same function). The target quality's sane-size
        // ceiling applies to them.
        byQuality[null]?.let { pool ->
            pickFromPool(pool, targetQuality)?.let { return it }
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
        // The ceiling is a hard filter: when every candidate at this tier
        // is KNOWN to be oversize, this tier yields no pick (pickBestSeeded
        // moves on to the next tier, and to null overall) rather than
        // auto-playing the oversize stream anyway.
        if (sane.isEmpty()) return null
        // Prefer known-size sane streams; unknown sizes are allowed but rank lower.
        val ordered = sane.filter { parseSizeBytes(it) != null } + sane.filter { parseSizeBytes(it) == null }
        // maxByOrNull keeps the first of equal scores, so the addon's ordering breaks ties.
        return ordered.maxByOrNull { parseSeeders(it) ?: -1 }
    }

    /**
     * Fallback for an English pool that is non-empty but entirely oversize:
     * the least-oversize member — smallest excess over its own tier's sane
     * ceiling (the target quality's ceiling for unparseable resolutions,
     * matching [pickFromPool]'s last-tier treatment) — then best-seeded,
     * mirroring [pickFromPool]'s ranking; further ties keep the addon's
     * ordering (minWithOrNull keeps the first minimum). Only known-oversize
     * streams can appear here: sane and unknown-size streams are pickable
     * by [pickFromPool], so [pickBestSeeded]'s English recursion would
     * already have returned one. Used ONLY for the English pool — the
     * non-English pool keeps the hard ceiling and yields no fallback.
     */
    private fun pickOversizeFallback(pool: List<StreamItem>, targetQuality: String): StreamItem? {
        val oversize = pool.mapNotNull { stream ->
            val size = parseSizeBytes(stream) ?: return@mapNotNull null
            val ceiling = saneSizeBytesByQuality[stream.streamResolution() ?: targetQuality]
                ?: return@mapNotNull null
            if (size <= ceiling) null else stream to (size - ceiling)
        }
        return oversize.minWithOrNull(
            compareBy<Pair<StreamItem, Long>> { it.second }
                .thenByDescending { parseSeeders(it.first) ?: -1 },
        )?.first
    }
}
