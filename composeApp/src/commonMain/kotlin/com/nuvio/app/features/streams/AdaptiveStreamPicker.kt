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

// Dubbed / dual-audio / multi-audio / english-dub indicators (word-boundaried).
private val englishDubIndicators = Regex(
    "\\bdubbed\\b|\\bdual[\\s\\-]?audio\\b|\\bmulti[\\s\\-]?audio\\b|\\benglish[\\s\\-]?dub\\b",
    RegexOption.IGNORE_CASE,
)
// Standalone "eng" language tag ...
private val engAudioTag = Regex("\\beng\\b", RegexOption.IGNORE_CASE)
// ... but not when it's part of a subtitle mention ("english subtitles", "subs: eng").
private val engSubtitleMention = Regex(
    "\\beng(lish)?\\b.{0,16}?\\bsubs?(titles?)?\\b|\\bsubs?(titles?)?\\b.{0,16}?\\beng(lish)?\\b",
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
     * audio language setting (default "en").
     */
    fun shouldPreferEnglishAudio(preferredAudioLanguage: String): Boolean =
        preferredAudioLanguage.trim().equals("en", ignoreCase = true)

    /**
     * True when the stream's metadata indicates English-dubbed/multi-audio.
     * "English subtitles" / "eng subs" (subtitle mentions) do NOT count.
     */
    fun hasEnglishAudio(stream: StreamItem): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        if (text.isBlank()) return false
        if (englishDubIndicators.containsMatchIn(text)) return true
        // A standalone "eng" language tag counts, unless it's part of a subtitle mention.
        val audioOnly = engSubtitleMention.replace(text, " ")
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
     * list so we never pick nothing when streams exist.
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
        val eligible = if (requireHdrSeeders) {
            streams.filter { stream ->
                !isHdrStream(stream) || (parseSeeders(stream) ?: -1) >= MIN_SEEDERS_FOR_HDR_AUTOPLAY
            }.ifEmpty { streams }
        } else {
            streams
        }
        if (preferEnglishAudio) {
            pickBestSeeded(eligible.filter { hasEnglishAudio(it) }, targetQuality)?.let { return it }
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
