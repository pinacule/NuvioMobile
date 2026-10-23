package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve

internal class TorboxFileSelector {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): TorboxTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.displayName() },
        isPlayable = {
            it.mimeType.orEmpty().startsWith("video/", ignoreCase = true) || it.displayName().hasVideoExtension()
        },
        size = { it.size ?: 0L },
    )
}

internal class RealDebridFileSelector {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): RealDebridTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { it.displayName().hasVideoExtension() },
        size = { it.bytes ?: 0L },
    )
}

internal class PremiumizeDirectDownloadFileSelector {
    fun selectFile(
        files: List<PremiumizeDirectDownloadFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): PremiumizeDirectDownloadFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { !it.link.isNullOrBlank() && it.displayName().hasVideoExtension() },
        size = { it.size ?: 0L },
    )
}

internal fun PremiumizeDirectDownloadFileDto.displayName(): String =
    path.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { path.orEmpty() }

private fun <T> selectDebridFile(
    files: List<T>,
    resolve: StreamClientResolve,
    season: Int?,
    episode: Int?,
    path: (T) -> String,
    isPlayable: (T) -> Boolean,
    size: (T) -> Long,
): T? {
    val playable = files.filter(isPlayable)
    if (playable.isEmpty()) return null

    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename)
        .map { it.normalizedPath() }
        .filter { it.isNotBlank() }
        .distinct()
    for (name in names) {
        val matches = playable.matchingFiles(name, path)
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    val effectiveSeason = season ?: resolve.season
    val effectiveEpisode = episode ?: resolve.episode
    val episodePattern = buildEpisodePattern(effectiveSeason, effectiveEpisode)
    if (episodePattern != null) {
        val matches = playable.filter {
            episodePattern.containsMatchIn(path(it).normalizedPath().substringAfterLast('/'))
        }
        if (matches.isNotEmpty()) {
            return pickEpisodeMatch(matches, path) { basename ->
                val match = episodePattern.find(basename)
                match == null || !hasEpisodeContinuation(basename, match)
            }
        }

        // Last-resort fallback for packs whose files carry no SxxExx/NxN marker
        // at all (absolute- or episode-numbered names such as
        // "Re:Zero - 02.mkv" or "[Group] Show E02.mkv"). Only reached when the
        // episode pattern above found nothing.
        if (effectiveEpisode != null) {
            selectEpisodeTokenFile(playable, resolve, effectiveEpisode, path)?.let { return it }
        }
    }

    if (names.isNotEmpty() || episodePattern != null) return null

    resolve.fileIdx?.let { index ->
        return files.getOrNull(index)?.takeIf(isPlayable)
    }

    return playable.maxByOrNull(size)
}

private fun String.normalizedPath(): String = trim().replace('\\', '/').removePrefix("/")

private fun <T> List<T>.matchingFiles(name: String, path: (T) -> String): List<T> {
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            val filePath = path(it).normalizedPath()
            filePath.equals(name, ignoreCase = ignoreCase) ||
                (name.contains('/') && filePath.endsWith("/$name", ignoreCase = ignoreCase))
        }
        if (matches.isNotEmpty()) return matches
    }
    val basename = name.substringAfterLast('/')
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            path(it).normalizedPath().substringAfterLast('/').equals(basename, ignoreCase = ignoreCase)
        }
        if (matches.isNotEmpty()) return matches
    }
    return emptyList()
}

private fun buildEpisodePattern(season: Int?, episode: Int?): Regex? {
    if (season == null || episode == null) return null
    return Regex(
        "(?<![a-z0-9])(?:s0*${season}e0*${episode}|0*${season}x0*${episode})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Picks one file out of several episode-pattern matches instead of giving up.
 * Files whose marker denotes exactly the requested single episode win over
 * multi-episode files (e.g. "S02E01.mkv" over "S02E01E02.mkv" or
 * "S02E01-02.mkv"); remaining ties break on the shortest filename, then on the
 * filename itself so the result does not depend on provider file order.
 * A single match is returned untouched.
 */
private fun <T> pickEpisodeMatch(
    matches: List<T>,
    path: (T) -> String,
    isSingleEpisode: (basename: String) -> Boolean,
): T {
    if (matches.size == 1) return matches.single()
    val singleEpisode = matches.filter {
        isSingleEpisode(path(it).normalizedPath().substringAfterLast('/'))
    }
    val pool = singleEpisode.ifEmpty { matches }
    return pool.minWithOrNull(
        compareBy<T> { path(it).normalizedPath().substringAfterLast('/').length }
            .thenBy { path(it).normalizedPath().substringAfterLast('/').lowercase() },
    ) ?: matches.first()
}

/** True when [match] is immediately followed by another episode (E02, -02, -E02). */
private fun hasEpisodeContinuation(basename: String, match: MatchResult): Boolean {
    val remainder = basename.substring(match.range.last + 1)
    return episodeContinuationPattern.containsMatchIn(remainder)
}

private val episodeContinuationPattern = Regex(
    "^(?:e0*[0-9]+(?![0-9])|[\\s._]*-[\\s._]*(?:e\\s*)?0*[0-9]+(?![0-9]))",
    RegexOption.IGNORE_CASE,
)

/** Any SxxExx/NxN marker, for any season/episode — used to keep the token fallback out of marked files. */
private val anyEpisodeMarkerPattern = Regex(
    "(?<![a-z0-9])(?:s0*[0-9]+e0*[0-9]+|0*[0-9]+x0*[0-9]+)(?![0-9])",
    RegexOption.IGNORE_CASE,
)

/**
 * Bare episode-number tokens at filename token boundaries: "- 26", "-26",
 * "E26" or "EP26". The number is matched with optional leading zeros and must
 * not be part of a longer number.
 */
private fun buildEpisodeTokenPattern(episode: Int): Regex =
    Regex(
        "(?:(?<![0-9])-\\s*0*${episode}(?![0-9])|(?<![a-z0-9])(?:ep|e)0*${episode}(?![0-9]))",
        RegexOption.IGNORE_CASE,
    )

/**
 * Last-resort match on a bare episode-number token, for packs whose files have
 * no SxxExx/NxN marker at all.
 *
 * Limitation: no absolute episode number, season offset, or per-season episode
 * count reaches this layer (the selectors only receive season + episode and
 * [StreamClientResolve] carries no such field), so the token searched for is
 * always the episode number itself. For season 1 that IS the absolute number
 * ("Re:Zero - 02" = S01E02); for later seasons it catches packs numbered
 * within the season ("- 01" = S02E01 inside a season-2 pack) but it cannot
 * derive a true absolute number (e.g. "- 26" for S02E01) — such files still
 * return null here rather than risking a wrong pick.
 *
 * Files carrying any SxxExx/NxN marker are excluded (a marked file belongs to
 * whatever episode its marker says), and when a series title is available its
 * tokens must all appear in the filename, so a same-numbered file from another
 * show is never picked.
 */
private fun <T> selectEpisodeTokenFile(
    playable: List<T>,
    resolve: StreamClientResolve,
    episode: Int,
    path: (T) -> String,
): T? {
    val tokenPattern = buildEpisodeTokenPattern(episode)
    val titleTokens = seriesTitleTokens(resolve)
    val matches = playable.filter { file ->
        val basename = path(file).normalizedPath().substringAfterLast('/')
        if (anyEpisodeMarkerPattern.containsMatchIn(basename)) return@filter false
        if (titleTokens.isNotEmpty() && !matchesSeriesTitle(basename, titleTokens)) return@filter false
        tokenPattern.containsMatchIn(basename)
    }
    if (matches.isEmpty()) return null
    return pickEpisodeMatch(matches, path) { basename ->
        val match = tokenPattern.find(basename)
        match == null || !hasEpisodeContinuation(basename, match)
    }
}

/**
 * Title tokens for the token fallback, from the parsed title when the addon
 * supplied one, else the resolve title. Years and other purely numeric tokens
 * are dropped, as are single letters. Empty when no usable title is known, in
 * which case the fallback applies no title gate.
 */
private fun seriesTitleTokens(resolve: StreamClientResolve): List<String> {
    val title = resolve.stream?.raw?.parsed?.parsedTitle?.takeIf { it.isNotBlank() }
        ?: resolve.title?.takeIf { it.isNotBlank() }
        ?: return emptyList()
    return title.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { token -> token.length >= 2 && token.any { it.isLetter() } }
        .distinct()
}

private fun matchesSeriesTitle(basename: String, titleTokens: List<String>): Boolean {
    val fileTokens = basename.lowercase().split(Regex("[^a-z0-9]+")).toSet()
    return titleTokens.all { it in fileTokens }
}

private fun String.hasVideoExtension(): Boolean = videoExtensions.any { endsWith(it, ignoreCase = true) }

private val videoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv",
)
