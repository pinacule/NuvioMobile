package com.nuvio.app.features.player.skip

import com.nuvio.app.features.simkl.SimklApi
import com.nuvio.app.features.simkl.SimklApiRequest
import com.nuvio.app.features.simkl.SimklConfig
import com.nuvio.app.features.simkl.SimklHttpMethod
import com.nuvio.app.features.simkl.SimklPlatformClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal object SimklIdResolver {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ResolvedIds(
        val simklId: Long,
        val type: String,
        val mal: String? = null,
        val anilist: String? = null,
        val kitsu: String? = null,
        val imdb: String? = null,
        val tvdbSeason: Int? = null
    )

    data class EpisodeMapping(
        val animeEpisode: Int,
        val tvdbSeason: Int,
        val tvdbEpisode: Int
    )

    // SkipIntroRepository reaches this resolver from parallel async callers, so
    // every cache access goes through cacheMutex (the ParentalGuideRepository
    // pattern: short locked sections, network calls stay outside the lock).
    private val cacheMutex = Mutex()
    private val idsCache = HashMap<String, ResolvedIds?>()
    private val episodeCache = HashMap<Long, List<EpisodeMapping>>()
    private val animeSeasonCache = HashMap<String, List<AnimeSeasonEntry>>()

    // Negative cache: epoch-ms of the last failed lookup per cache key. A
    // failing Simkl API used to be re-hit on every playback attempt because
    // failures were never recorded; within NEGATIVE_CACHE_TTL_MS a known-bad
    // key short-circuits to the same null/empty result a fresh failure gives.
    private val failedLookups = HashMap<String, Long>()
    private const val NEGATIVE_CACHE_TTL_MS = 60_000L

    /**
     * Runs a GET through the shared Simkl API client so these lookups inherit
     * its pacing, mutex, retry classification and error mapping (previously
     * this resolver issued raw httpGetText calls with none of those). The
     * client also appends client_id/app-name/app-version itself, using the
     * real app version. The endpoints used here are public (client_id only)
     * and skip-intro resolution must keep working while the user is signed
     * out of Simkl, so no account token is required.
     */
    private suspend fun fetchSimklText(path: String, query: Map<String, String> = emptyMap()): String =
        SimklApi.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = path,
                query = query,
                requiresAuthentication = false,
            ),
        ).body

    private suspend fun hasRecentFailure(cacheKey: String): Boolean = cacheMutex.withLock {
        val failedAt = failedLookups[cacheKey] ?: return@withLock false
        if (SimklPlatformClock.nowEpochMs() - failedAt in 0..NEGATIVE_CACHE_TTL_MS) {
            true
        } else {
            failedLookups.remove(cacheKey)
            false
        }
    }

    private suspend fun recordFailure(cacheKey: String) {
        cacheMutex.withLock {
            failedLookups[cacheKey] = SimklPlatformClock.nowEpochMs()
        }
    }

    suspend fun resolveIds(source: String, id: String, contentTypeHint: String? = null): ResolvedIds? {
        val cacheKey = if (contentTypeHint != null) "$source:$id:$contentTypeHint" else "$source:$id"
        cacheMutex.withLock {
            if (idsCache.containsKey(cacheKey)) return idsCache[cacheKey]
        }
        if (SimklConfig.CLIENT_ID.isBlank()) return null
        if (hasRecentFailure(cacheKey)) return null

        return try {
            val searchText = fetchSimklText("/search/id", mapOf(source to id))
            val results = json.parseToJsonElement(searchText).jsonArray
            if (results.isEmpty()) return null

            val matchedResult = if (contentTypeHint != null) {
                val expectedTypes = when (contentTypeHint.lowercase()) {
                    "movie", "film" -> setOf("movie")
                    "series", "tv", "show", "tvshow" -> setOf("show", "tv")
                    "anime" -> setOf("anime")
                    else -> emptySet()
                }
                results.firstOrNull { entry ->
                    val t = entry.jsonObject["type"]?.jsonPrimitive?.content
                    t != null && t in expectedTypes
                } ?: results[0]
            } else {
                results[0]
            }

            val simklId = matchedResult.jsonObject["ids"]?.jsonObject?.get("simkl")?.jsonPrimitive?.long ?: return null

            val type = matchedResult.jsonObject["type"]?.jsonPrimitive?.content ?: "anime"
            val mediaType = when (type) {
                "movie" -> "movies"
                "show" -> "tv"
                else -> "anime"
            }

            val detailsText = fetchSimklText("/$mediaType/$simklId", mapOf("extended" to "full"))
            val details = json.parseToJsonElement(detailsText).jsonObject
            val ids = details["ids"]?.jsonObject

            val resolved = ResolvedIds(
                simklId = simklId,
                type = mediaType,
                mal = ids?.get("mal")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                anilist = ids?.get("anilist")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                kitsu = ids?.get("kitsu")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                imdb = ids?.get("imdb")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                tvdbSeason = details["season"]?.jsonPrimitive?.int?.takeIf { it > 0 }
            )
            cacheMutex.withLock { idsCache[cacheKey] = resolved }
            resolved
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            recordFailure(cacheKey)
            null
        }
    }

    suspend fun getEpisodeMapping(simklId: Long, type: String = "anime"): List<EpisodeMapping> {
        cacheMutex.withLock {
            episodeCache[simklId]?.let { return it }
        }
        if (SimklConfig.CLIENT_ID.isBlank()) return emptyList()
        val failureKey = "episodes:$simklId"
        if (hasRecentFailure(failureKey)) return emptyList()

        return try {
            val text = fetchSimklText("/$type/episodes/$simklId")
            val episodes = json.parseToJsonElement(text).jsonArray
            val mapping = mutableListOf<EpisodeMapping>()
            for (ep in episodes) {
                val obj = ep.jsonObject
                val epNum = obj["episode"]?.jsonPrimitive?.int ?: continue
                val tvdb = obj["tvdb"]?.jsonObject ?: continue
                val tvdbSeason = tvdb["season"]?.jsonPrimitive?.int ?: continue
                val tvdbEpisode = tvdb["episode"]?.jsonPrimitive?.int ?: continue
                if (epNum > 0 && tvdbSeason > 0 && tvdbEpisode > 0) {
                    mapping.add(EpisodeMapping(epNum, tvdbSeason, tvdbEpisode))
                }
            }
            cacheMutex.withLock { episodeCache[simklId] = mapping }
            mapping
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            recordFailure(failureKey)
            emptyList()
        }
    }

    suspend fun resolveEpisodeTvdb(source: String, id: String, episode: Int): Pair<Int, Int>? {
        val ids = resolveIds(source, id) ?: return null
        val entry = getEpisodeMapping(ids.simklId, ids.type).firstOrNull { it.animeEpisode == episode }
        return entry?.let { it.tvdbSeason to it.tvdbEpisode }
    }

    /**
     * Given an IMDB ID and a TVDB-style season+episode, resolve the anime-specific IDs
     * (MAL, AniList, Kitsu) for the correct season entry.
     */
    suspend fun resolveIdsForImdbEpisode(
        imdbId: String,
        season: Int,
        episode: Int
    ): ResolvedIds? {
        val base = resolveIds("imdb", imdbId) ?: return null
        if (base.type != "anime") return base

        // If the parent entry already owns this season, no sibling lookup needed.
        val baseSeason = base.tvdbSeason
        if (baseSeason != null && baseSeason == season) return base

        // Fetch the parent with full_anime_seasons to discover per-season entries.
        val seasonSimklId = resolveSeasonSimklId(base.simklId, base.type, season)
        if (seasonSimklId != null && seasonSimklId != base.simklId) {
            val siblingIds = resolveIdsBySimklId(seasonSimklId, base.type)
            if (siblingIds != null) return siblingIds
        }

        return base
    }

    private suspend fun resolveSeasonSimklId(parentSimklId: Long, type: String, tvdbSeason: Int): Long? {
        val cacheKey = "anime_seasons:$parentSimklId"
        cacheMutex.withLock {
            animeSeasonCache[cacheKey]?.let { seasons ->
                return seasons.firstOrNull { it.tvdbSeason == tvdbSeason }?.simklId
            }
        }
        if (hasRecentFailure(cacheKey)) return null

        return try {
            val text = fetchSimklText(
                "/$type/$parentSimklId",
                mapOf("extended" to "full_anime_seasons"),
            )
            val details = json.parseToJsonElement(text).jsonObject
            val seasonsArray = details["mapped_tvdb_seasons"]?.jsonArray ?: return null

            val seasons = mutableListOf<AnimeSeasonEntry>()
            for (entry in seasonsArray) {
                val obj = entry.jsonObject
                val simklId = obj["simkl_id"]?.jsonPrimitive?.long?.takeIf { it > 0 } ?: continue
                val mappedSeason = obj["tvdb_season"]?.jsonPrimitive?.int?.takeIf { it > 0 } ?: continue
                seasons.add(AnimeSeasonEntry(simklId, mappedSeason))
            }
            cacheMutex.withLock { animeSeasonCache[cacheKey] = seasons }
            seasons.firstOrNull { it.tvdbSeason == tvdbSeason }?.simklId
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            recordFailure(cacheKey)
            null
        }
    }

    private suspend fun resolveIdsBySimklId(simklId: Long, type: String): ResolvedIds? {
        val cacheKey = "simkl:$simklId"
        cacheMutex.withLock {
            if (idsCache.containsKey(cacheKey)) return idsCache[cacheKey]
        }
        if (hasRecentFailure(cacheKey)) return null

        return try {
            val text = fetchSimklText("/$type/$simklId", mapOf("extended" to "full"))
            val details = json.parseToJsonElement(text).jsonObject
            val ids = details["ids"]?.jsonObject

            val resolved = ResolvedIds(
                simklId = simklId,
                type = type,
                mal = ids?.get("mal")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                anilist = ids?.get("anilist")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                kitsu = ids?.get("kitsu")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                imdb = ids?.get("imdb")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                tvdbSeason = details["season"]?.jsonPrimitive?.int?.takeIf { it > 0 }
            )
            cacheMutex.withLock { idsCache[cacheKey] = resolved }
            resolved
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            recordFailure(cacheKey)
            null
        }
    }

    fun clearCache() {
        // Non-suspend by contract with SkipIntroRepository.clearCache. All
        // locked sections above are short map operations that never span a
        // network call, so tryLock effectively always succeeds; if a lookup
        // is mid-update the clear is skipped rather than blocking the caller.
        if (cacheMutex.tryLock()) {
            try {
                idsCache.clear()
                episodeCache.clear()
                animeSeasonCache.clear()
                failedLookups.clear()
            } finally {
                cacheMutex.unlock()
            }
        }
    }
}

private data class AnimeSeasonEntry(val simklId: Long, val tvdbSeason: Int)
