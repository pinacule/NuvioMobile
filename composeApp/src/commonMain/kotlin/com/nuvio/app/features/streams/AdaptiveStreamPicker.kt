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
     * Best-seeded stream at [targetQuality] within the sane size band. Streams with an
     * unknown size are allowed but rank below known sane-sized ones. Unknown seeder
     * counts rank below known ones. Ties keep the addon's own ordering.
     *
     * Falls back to the closest available quality (preferring the lower one on ties)
     * when nothing matches the target. Returns null when [streams] is empty.
     */
    fun pickBestSeeded(streams: List<StreamItem>, targetQuality: String): StreamItem? {
        if (streams.isEmpty()) return null
        val byQuality = streams.groupBy { it.streamResolution() }
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
