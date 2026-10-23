package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.AdaptiveStreamPicker
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.streamResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

sealed class SeasonDownloadResult {
    data class Queued(
        val queued: Int,
        val alreadyDownloaded: Int,
        val missingEpisodes: List<Int>,
        /** A target with no episode number (a movie) could not be started. */
        val missingMovie: Boolean = false,
    ) : SeasonDownloadResult()

    /** The chosen stream needs P2P, which is turned off. */
    data object P2pDisabled : SeasonDownloadResult()

    /** The season's episode list could not be loaded. */
    data object NoEpisodes : SeasonDownloadResult()

    /** The chosen stream does not come from an installed addon, so other episodes cannot be looked up. */
    data object UnsupportedSource : SeasonDownloadResult()
}

/**
 * Downloads released episodes using the stream the user picked as the template:
 * the same torrent when it is a season pack, otherwise the closest stream of the same addon, kind
 * and resolution.
 */
object SeasonDownloads {
    private val log = Logger.withTag("SeasonDownloads")
    private const val FETCH_PARALLELISM = 3

    private fun checkP2pAllowed(template: StreamItem): Boolean {
        P2pSettingsRepository.ensureLoaded()
        return !template.needsP2pDownload() || P2pSettingsRepository.uiState.value.p2pEnabled
    }

    private fun findTemplateAddon(template: StreamItem): AddonManifest? =
        AddonRepository.uiState.value.addons.enabledAddons().firstNotNullOfOrNull { addon ->
            val manifest = addon.manifest ?: return@firstNotNullOfOrNull null
            manifest.takeIf { addon.streamAddonInstanceId(it.id) == template.addonId }
        }

    private suspend fun loadSeasonEpisodes(
        parentMetaType: String,
        parentMetaId: String,
        seasonNumber: Int,
        fromEpisode: Int,
        toEpisode: Int?,
    ): List<MetaVideo> {
        val meta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
            ?: MetaDetailsRepository.fetch(parentMetaType, parentMetaId)
        return meta?.videos.orEmpty()
            .filter { it.season == seasonNumber && it.episode != null && it.available }
            .filter { video ->
                val episode = video.episode ?: return@filter false
                episode >= fromEpisode && (toEpisode == null || episode <= toEpisode)
            }
            .distinctBy { it.episode }
            .sortedBy { it.episode }
    }

    suspend fun enqueueSeason(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int,
        template: StreamItem,
        templateVideoId: String,
    ): SeasonDownloadResult {
        if (!checkP2pAllowed(template)) return SeasonDownloadResult.P2pDisabled
        val addon = findTemplateAddon(template) ?: return SeasonDownloadResult.UnsupportedSource
        val episodes = loadSeasonEpisodes(parentMetaType, parentMetaId, seasonNumber, fromEpisode = 1, toEpisode = null)
        if (episodes.isEmpty()) return SeasonDownloadResult.NoEpisodes
        return enqueueVideos(
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            targets = episodes,
            addon = addon,
            template = template,
            templateVideoId = templateVideoId,
        )
    }

    /**
     * Downloads [fromEpisode]..[toEpisode] (inclusive, [toEpisode] = null means end of season)
     * of [seasonNumber], matching each episode to [template]'s addon/kind/resolution.
     */
    suspend fun enqueueEpisodeRange(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int,
        fromEpisode: Int,
        toEpisode: Int?,
        template: StreamItem,
        templateVideoId: String,
    ): SeasonDownloadResult {
        if (!checkP2pAllowed(template)) return SeasonDownloadResult.P2pDisabled
        val addon = findTemplateAddon(template) ?: return SeasonDownloadResult.UnsupportedSource
        val episodes = loadSeasonEpisodes(parentMetaType, parentMetaId, seasonNumber, fromEpisode, toEpisode)
        if (episodes.isEmpty()) return SeasonDownloadResult.NoEpisodes
        return enqueueVideos(
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            targets = episodes,
            addon = addon,
            template = template,
            templateVideoId = templateVideoId,
        )
    }

    /**
     * Downloads exactly [targets] (already resolved list, may span seasons; a target
     * with no episode number is a movie), matching each target's stream to [template].
     */
    suspend fun enqueueVideos(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        targets: List<MetaVideo>,
        addon: AddonManifest,
        template: StreamItem,
        templateVideoId: String,
    ): SeasonDownloadResult {
        if (!checkP2pAllowed(template)) return SeasonDownloadResult.P2pDisabled
        DownloadsRepository.ensureLoaded()
        runCatching { PlayerSettingsRepository.ensureLoaded() }
        // Dub over sub, always: when matching other episodes to the template,
        // prefer English-audio releases (across resolutions — see
        // pickMatchingEpisodeStream).
        val preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
            PlayerSettingsRepository.uiState.value.preferredAudioLanguage,
        )
        val (alreadyDownloaded, wanted) = targets.partition { video ->
            DownloadsRepository.hasActiveOrCompletedDownload(parentMetaId, video.season, video.episode)
        }

        val picks: List<Pair<MetaVideo, StreamItem?>> = wanted.chunked(FETCH_PARALLELISM).flatMap { batch ->
            coroutineScope {
                batch.map { video ->
                    async {
                        val stream = if (video.id == templateVideoId) {
                            template
                        } else {
                            val candidates = fetchEpisodeStreams(addon, contentType, video.id, template)
                            pickMatchingEpisodeStream(
                                template,
                                candidates,
                                preferEnglishAudio,
                                targetSeason = video.season,
                                targetEpisode = video.episode,
                            )
                        }
                        video to stream?.let { resolvePlayable(it, video.season ?: 0, video.episode) }
                    }
                }.awaitAll()
            }
        }

        var queued = 0
        val missing = mutableListOf<Int>()
        var missingMovie = false
        picks.forEach { (video, stream) ->
            val episodeNumber = video.episode
            val result = stream?.let {
                DownloadsRepository.enqueueFromStream(
                    contentType = contentType,
                    videoId = video.id,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = video.season,
                    episodeNumber = episodeNumber,
                    episodeTitle = video.title.takeIf(String::isNotBlank),
                    episodeThumbnail = video.thumbnail,
                    stream = it,
                    downloadQuality = it.streamResolution() ?: template.streamResolution(),
                )
            }
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                queued += 1
            } else if (episodeNumber != null) {
                missing += episodeNumber
            } else {
                // No episode number: the target is a movie that could not be started.
                missingMovie = true
            }
        }
        return SeasonDownloadResult.Queued(
            queued = queued,
            alreadyDownloaded = alreadyDownloaded.size,
            missingEpisodes = missing,
            missingMovie = missingMovie,
        )
    }

    private suspend fun fetchEpisodeStreams(
        manifest: AddonManifest,
        contentType: String,
        videoId: String,
        template: StreamItem,
    ): List<StreamItem> = fetchStreamsForVideo(
        manifest = manifest,
        contentType = contentType,
        videoId = videoId,
        addonName = template.addonName,
        addonId = template.addonId,
        addonLogo = template.addonLogo,
    )

    /**
     * Fetches raw streams for a single episode video from one addon manifest.
     * Used by the download planner (quality picker / preload) which has no template yet.
     */
    internal suspend fun fetchStreamsForVideo(
        manifest: AddonManifest,
        contentType: String,
        videoId: String,
        addonName: String,
        addonId: String,
        addonLogo: String?,
    ): List<StreamItem> = try {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "stream",
            type = contentType,
            id = videoId,
        )
        StreamParser.parse(
            payload = fetchAddonResponseText(url = url),
            addonName = addonName,
            addonId = addonId,
            addonLogo = addonLogo,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        log.w(error) { "Could not load streams for $videoId" }
        emptyList()
    }

    /**
     * Resolves debrid streams to playable HTTP URLs, mirroring the enqueue
     * path. Internal so the stale-link refresh can reuse the exact same
     * resolution for a re-picked stream.
     */
    internal suspend fun resolvePlayable(stream: StreamItem, season: Int, episode: Int?): StreamItem? =
        when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)) {
            is DirectDebridPlayableResult.Success -> resolved.stream
            else -> null
        }

    private fun StreamItem.needsP2pDownload(): Boolean =
        isTorrentStream &&
            playableDirectUrl == null &&
            !DirectDebridPlaybackResolver.shouldResolveToPlayableStream(this)
}

/**
 * Picks the episode stream that best matches [template]: the same torrent (a season pack) first,
 * otherwise a stream of the same kind and resolution whose release name is closest.
 * When no stream exists at the template's resolution, the closest available quality
 * is used instead of giving up (see [matchingResolutionPool]).
 * When [preferEnglishAudio], an English-audio release wins across
 * resolutions (and across stream kinds) before that same-resolution matching runs (dub over sub, always) — and
 * before the season-pack hash shortcut too, unless the pack itself is the dub: a
 * non-English pack must not swallow episodes that have their own English release.
 * When the target's [targetSeason]/[targetEpisode] are known, candidates whose
 * name/title/filename carries an explicit season/episode marker for a DIFFERENT episode
 * are dropped before any ranking (the dub branch ranks by seeders ahead of
 * name similarity, so a mislabelled S02E02 dub could otherwise beat the correct
 * S02E01 release). Candidates with no explicit marker stay eligible, as does
 * the template-hash match itself; without target coordinates (a movie) the
 * check is skipped.
 */
internal fun pickMatchingEpisodeStream(
    template: StreamItem,
    candidates: List<StreamItem>,
    preferEnglishAudio: Boolean = false,
    targetSeason: Int? = null,
    targetEpisode: Int? = null,
): StreamItem? {
    val templateHash = template.p2pInfoHash?.lowercase()
    val eligible = if (targetSeason != null && targetEpisode != null) {
        candidates.filter { candidate ->
            val isHashMatch = templateHash != null &&
                candidate.p2pInfoHash?.lowercase() == templateHash
            isHashMatch || !candidate.hasConflictingEpisodeMarker(targetSeason, targetEpisode)
        }
    } else {
        candidates
    }
    val hashMatch = templateHash?.let { hash ->
        eligible.firstOrNull { it.p2pInfoHash?.lowercase() == hash }
    }
    if (hashMatch != null) {
        // The pack shortcut is safe for the dub rule only when the preference is
        // off, the pack itself carries English audio, or no candidate does.
        val dubDefeated = preferEnglishAudio &&
            !AdaptiveStreamPicker.hasEnglishAudio(hashMatch) &&
            eligible.any { AdaptiveStreamPicker.hasEnglishAudio(it) }
        if (!dubDefeated) return hashMatch
    }
    val templateResolution = template.streamResolution()
    if (preferEnglishAudio) {
        // Dub over sub, always: an English-audio release at ANY resolution beats
        // a same-resolution non-English one. Among dubs, the template's own
        // resolution wins first, then best-seeded, then the closest release
        // name; ties keep the addon's own ordering (maxWithOrNull keeps the
        // first of equal scores). The candidate set stays the template addon's
        // streams, but — like EpisodeDownloadPlanner.pickBestStreamAtQuality —
        // it is NOT restricted to the template's kind: a dub that exists only
        // as a direct HTTP/debrid stream still counts. Only the non-English
        // matching below keeps the same-kind restriction.
        val englishTokens = template.releaseTokens()
        eligible
            .filter { AdaptiveStreamPicker.hasEnglishAudio(it) }
            .maxWithOrNull(
                compareBy<StreamItem> { if (it.streamResolution() == templateResolution) 1 else 0 }
                    .thenBy { AdaptiveStreamPicker.parseSeeders(it) ?: -1 }
                    .thenBy { jaccard(englishTokens, it.releaseTokens()) },
            )
            ?.let { return it }
        // No dub in the English pool (e.g. only a different-kind one): the pack
        // shortcut still applies, ahead of the same-resolution matching below.
        hashMatch?.let { return it }
    }
    val sameKind = eligible.filter { it.isTorrentStream == template.isTorrentStream }
    val pool = matchingResolutionPool(sameKind, templateResolution)
    if (pool.isEmpty()) return null
    // Dub over sub: prefer English-audio releases at the template's quality.
    // The English narrowing is not kind-restricted either (see the block
    // above): English releases of any kind, narrowed to the closest
    // resolution, form the fallback English pool. In practice the block
    // above has already returned any English candidate, so this only keeps
    // the two pools consistent.
    val rankedPool = if (preferEnglishAudio) {
        val english = pool.filter { AdaptiveStreamPicker.hasEnglishAudio(it) }
        if (english.isNotEmpty()) {
            english
        } else {
            matchingResolutionPool(
                eligible.filter { AdaptiveStreamPicker.hasEnglishAudio(it) },
                templateResolution,
            ).ifEmpty { pool }
        }
    } else {
        pool
    }
    val templateTokens = template.releaseTokens()
    // maxByOrNull keeps the first of equal scores, so the addon's own ordering breaks ties.
    return rankedPool.maxByOrNull { jaccard(templateTokens, it.releaseTokens()) }
}

/**
 * The fallback pool for [pickMatchingEpisodeStream]: the same-kind streams at the
 * template's resolution when any exist. When none do, the closest available quality
 * takes their place: the tier with the smallest distance from the template's tier
 * in [qualityTierRank], ties going to the lower tier — the same nearest-tier
 * ordering [AdaptiveStreamPicker.pickBestSeeded] uses for its quality fallback — so
 * an episode that only exists at another quality is still downloaded instead of
 * landing in missingEpisodes. Streams whose resolution cannot be parsed are a last
 * resort, used only when no parseable candidate exists at any tier. A null
 * [templateResolution] keeps every same-kind stream eligible, as before.
 */
private fun matchingResolutionPool(
    sameKind: List<StreamItem>,
    templateResolution: String?,
): List<StreamItem> {
    if (templateResolution == null) return sameKind
    val exact = sameKind.filter { it.streamResolution() == templateResolution }
    if (exact.isNotEmpty()) return exact
    val parseable = sameKind.filter { it.streamResolution() != null }
    if (parseable.isEmpty()) return sameKind
    val closest = closestQualityLabel(
        available = parseable.mapNotNull { it.streamResolution() }.distinct(),
        target = templateResolution,
    ) ?: return parseable
    return parseable.filter { it.streamResolution() == closest }
}

/** Quality tiers from lowest to highest, mirroring AdaptiveStreamPicker's ranking. */
private val qualityTierRank = listOf("480p", "576p", "720p", "1080p", "1440p", "2160p")

/** Closest of [available] to [target]: smallest tier distance, ties to the lower tier. */
private fun closestQualityLabel(available: List<String>, target: String): String? =
    available.minWithOrNull(
        compareBy<String> { kotlin.math.abs(qualityTierRank.indexOf(it) - qualityTierRank.indexOf(target)) }
            .thenBy { qualityTierRank.indexOf(it) },
    )

private val episodeMarker = Regex("\\bs\\d{1,2}\\s*e\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b|\\be(p(isode)?)?\\s*\\d{1,3}\\b")

// Explicit season/episode markers with capturable numbers: the SxxExx and NxN
// shapes from [episodeMarker] (the bare "E05" shape carries no season, so it
// cannot prove a mismatch on its own and is not used here).
private val seasonEpisodeMarker = Regex("\\bs(\\d{1,2})\\s*e(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
private val crossEpisodeMarker = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b")

// NxN pairs that are standard aspect ratios, not season/episode markers:
// "…1080p.WEB.16x9.…" names a 16:9 picture, not season 16 episode 9. The set
// is deliberately an explicit list of well-known ratios — 3:2, 4:3, 5:4,
// 9:16 (vertical), 16:9, 16:10, 17:9 (DCI), 21:9 and 32:9 — rather than a
// numeric range heuristic: a long-running show can genuinely reach a season
// in the teens or beyond, so only these exact pairs are exempted, and only
// from the NxN shape (an SxxExx marker is never an aspect ratio). Exempting a
// pair can at worst keep a candidate the name really meant as that episode
// (a miss); keeping the exemption list small and exact bounds that cost,
// while a false drop would empty the pool and mark the episode missing.
private val aspectRatioPairs = setOf(
    3 to 2, 4 to 3, 5 to 4, 9 to 16, 16 to 9, 16 to 10, 17 to 9, 21 to 9, 32 to 9,
)

// Episode-range markers: a full marker, a dash, and an endpoint that repeats
// the season ("S02E01-S02E02", "2x01-2x02"), repeats only the episode with its
// shape letter ("S02E01-E02"), or is a bare number ("2x01-02", "S02E01-02").
// Only this marker-dash-endpoint shape extends coverage — a bare endpoint
// anywhere else proves nothing — and the endpoint group must end on a word
// boundary, so a dash into a resolution ("S02E01-1080p") is not a range.
private val seasonEpisodeRangeMarker = Regex(
    "\\bs(\\d{1,2})\\s*e(\\d{1,3})\\s*-\\s*(?:s(\\d{1,2})\\s*e|e)?(\\d{1,3})\\b",
    RegexOption.IGNORE_CASE,
)
private val crossEpisodeRangeMarker = Regex(
    "\\b(\\d{1,2})x(\\d{1,3})\\s*-\\s*(?:(\\d{1,2})x)?(\\d{1,3})\\b",
)

/**
 * True when this stream's name/title/filename carries explicit season/episode
 * markers and NONE of them names [targetSeason]/[targetEpisode] — i.e. the
 * release is provably for a different episode. No marker at all, or any marker
 * matching the target (a name can carry several, e.g. a range), keeps the
 * stream eligible: false drops are worse than misses. The description is
 * deliberately not scanned — prose mentions other episodes too easily.
 * Two marker-like shapes are handled before judging: known aspect-ratio
 * pairs ([aspectRatioPairs]) are not episode claims and are ignored, and a
 * range ("S02E01-E03", "2x01-02") covers every episode from its start to
 * its endpoint, not just the two numbers written out.
 */
private fun StreamItem.hasConflictingEpisodeMarker(targetSeason: Int, targetEpisode: Int): Boolean {
    val text = listOfNotNull(name, title, behaviorHints.filename).joinToString(" ")
    val seasonMarkers = seasonEpisodeMarker.findAll(text).mapNotNull { match ->
        val s = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val e = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        s to e
    }
    val crossMarkers = crossEpisodeMarker.findAll(text).mapNotNull { match ->
        val s = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val e = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        s to e
    }.filter { it !in aspectRatioPairs }
    val parsed = (seasonMarkers + crossMarkers).toList()

    // Ranges as (season, first episode, last episode). A range only counts
    // when its endpoint stays in the start's season and does not run
    // backwards; the endpoints are already in [parsed] for the fully
    // repeated forms, so a range adds exactly the interior episodes and the
    // shorthand endpoints ("-E02", "-02") that [seasonEpisodeMarker] and
    // [crossEpisodeMarker] cannot capture on their own.
    val ranges = mutableListOf<Triple<Int, Int, Int>>()
    seasonEpisodeRangeMarker.findAll(text).forEach { match ->
        val startSeason = match.groupValues[1].toIntOrNull() ?: return@forEach
        val startEpisode = match.groupValues[2].toIntOrNull() ?: return@forEach
        val endSeason = match.groupValues[3].toIntOrNull() ?: startSeason
        val endEpisode = match.groupValues[4].toIntOrNull() ?: return@forEach
        if (endSeason == startSeason && endEpisode >= startEpisode) {
            ranges += Triple(startSeason, startEpisode, endEpisode)
        }
    }
    crossEpisodeRangeMarker.findAll(text).forEach { match ->
        val startSeason = match.groupValues[1].toIntOrNull() ?: return@forEach
        val startEpisode = match.groupValues[2].toIntOrNull() ?: return@forEach
        if ((startSeason to startEpisode) in aspectRatioPairs) return@forEach
        val endSeason = match.groupValues[3].toIntOrNull() ?: startSeason
        val endEpisode = match.groupValues[4].toIntOrNull() ?: return@forEach
        if (endSeason == startSeason && endEpisode >= startEpisode) {
            ranges += Triple(startSeason, startEpisode, endEpisode)
        }
    }

    val coversTarget = parsed.any { it.first == targetSeason && it.second == targetEpisode } ||
        ranges.any { (season, first, last) ->
            season == targetSeason && targetEpisode in first..last
        }
    return (parsed.isNotEmpty() || ranges.isNotEmpty()) && !coversTarget
}

/** Release-name words without episode numbers or bare numbers (sizes, seeders). */
private fun StreamItem.releaseTokens(): Set<String> =
    listOfNotNull(name, title, description, behaviorHints.filename)
        .joinToString(" ")
        .lowercase()
        .replace(episodeMarker, " ")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && !it.all(Char::isDigit) }
        .toSet()

private fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() && b.isEmpty()) return 0.0
    return (a intersect b).size.toDouble() / (a union b).size.toDouble()
}
