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
     * Downloads exactly [targets] (already resolved episode list, may span seasons),
     * matching each episode's stream to [template].
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
        DownloadsRepository.ensureLoaded()
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
                            pickMatchingEpisodeStream(template, candidates)
                        }
                        video to stream?.let { resolvePlayable(it, video.season ?: 0, video.episode) }
                    }
                }.awaitAll()
            }
        }

        var queued = 0
        val missing = mutableListOf<Int>()
        picks.forEach { (video, stream) ->
            val episodeNumber = video.episode ?: return@forEach
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
                )
            }
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                queued += 1
            } else {
                missing += episodeNumber
            }
        }
        return SeasonDownloadResult.Queued(
            queued = queued,
            alreadyDownloaded = alreadyDownloaded.size,
            missingEpisodes = missing,
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

    private suspend fun resolvePlayable(stream: StreamItem, season: Int, episode: Int?): StreamItem? =
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
 */
internal fun pickMatchingEpisodeStream(template: StreamItem, candidates: List<StreamItem>): StreamItem? {
    val templateHash = template.p2pInfoHash?.lowercase()
    if (templateHash != null) {
        candidates.firstOrNull { it.p2pInfoHash?.lowercase() == templateHash }?.let { return it }
    }
    val templateResolution = template.streamResolution()
    val pool = candidates
        .filter { it.isTorrentStream == template.isTorrentStream }
        .filter { templateResolution == null || it.streamResolution() == templateResolution }
    if (pool.isEmpty()) return null
    val templateTokens = template.releaseTokens()
    // maxByOrNull keeps the first of equal scores, so the addon's own ordering breaks ties.
    return pool.maxByOrNull { jaccard(templateTokens, it.releaseTokens()) }
}

private val episodeMarker = Regex("\\bs\\d{1,2}\\s*e\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b|\\be(p(isode)?)?\\s*\\d{1,3}\\b")

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
