package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.plugins.PluginRepositoryItem
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_plugin_repository_fallback
import org.jetbrains.compose.resources.getString

internal data class InstalledStreamAddonTarget(
    val addonName: String,
    val addonId: String,
    val manifest: AddonManifest,
)

internal fun ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"

internal data class PluginProviderGroup(
    val addonId: String,
    val addonName: String,
    val scrapers: List<PluginScraper>,
)

internal sealed interface StreamLoadCompletion {
    data class Addon(val group: AddonStreamGroup) : StreamLoadCompletion
    data class PluginScraper(
        val addonId: String,
        val streams: List<StreamItem>,
        val error: String?,
    ) : StreamLoadCompletion
}

internal fun List<PluginScraper>.toPluginProviderGroups(
    repositories: List<PluginRepositoryItem>,
    groupByRepository: Boolean,
): List<PluginProviderGroup> {
    if (!groupByRepository) {
        return map { scraper ->
            PluginProviderGroup(
                addonId = "plugin:${scraper.id}",
                addonName = scraper.name,
                scrapers = listOf(scraper),
            )
        }
    }

    val repoNameByUrl = repositories.associate { it.manifestUrl to it.name }
    return groupBy { it.repositoryUrl }
        .map { (repositoryUrl, scrapers) ->
            PluginProviderGroup(
                addonId = "plugin-repo:${repositoryUrl.lowercase()}",
                addonName = repoNameByUrl[repositoryUrl].orEmpty().ifBlank { repositoryUrl.fallbackRepositoryLabel() },
                scrapers = scrapers.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.addonName.lowercase() }
}

internal fun List<AddonStreamGroup>.toEmptyStateReason(anyLoading: Boolean): StreamsEmptyStateReason? {
    if (anyLoading || any { it.streams.isNotEmpty() }) {
        return null
    }

    return if (isNotEmpty() && all { !it.error.isNullOrBlank() }) {
        StreamsEmptyStateReason.StreamFetchFailed
    } else {
        StreamsEmptyStateReason.NoStreamsFound
    }
}

internal suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal fun PluginRuntimeResult.toStreamItem(
    scraper: PluginScraper,
    addonName: String = scraper.name,
    addonId: String = "plugin:${scraper.id}",
    includeScraperNameInSubtitle: Boolean = false,
): StreamItem {
    val subtitleParts = listOfNotNull(
        scraper.name.takeIf { includeScraperNameInSubtitle && it.isNotBlank() },
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    val requestHeaders = headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val headerName = key.trim()
            val headerValue = value.trim()
            if (headerName.isBlank() || headerValue.isBlank() || headerName.equals("Range", ignoreCase = true)) {
                null
            } else {
                headerName to headerValue
            }
        }
        .toMap()

    return StreamItem(
        name = name ?: title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        sourceName = scraper.name,
        addonName = addonName,
        addonId = addonId,
        streamType = normalizeStreamType(type),
        behaviorHints = if (requestHeaders.isEmpty()) {
            StreamBehaviorHints()
        } else {
            StreamBehaviorHints(
                notWebReady = true,
                proxyHeaders = StreamProxyHeaders(request = requestHeaders),
            )
        },
        externalSubtitles = subtitles?.map {
            StreamSubtitle(
                url = it.url,
                language = it.language,
                name = it.name,
                headers = it.headers
            )
        } ?: emptyList()
    )
}

internal fun List<StreamItem>.sortedForGroupedDisplay(
    preferEnglishAudio: Boolean = false,
): List<StreamItem> {
    // Best-seeded first; unknown counts last. The sort is stable,
    // so the addon's own order breaks remaining ties.
    val seedersThenLabel = compareByDescending<StreamItem> { AdaptiveStreamPicker.parseSeeders(it) ?: -1 }
        .thenBy { it.streamLabel.lowercase() }
        .thenBy { it.streamSubtitle.orEmpty().lowercase() }
    return if (preferEnglishAudio) {
        // Dub over sub, always: English-audio streams first across the WHOLE
        // group. In repo-merged plugin groups the scraper sourceName must not
        // outrank language (a non-English stream from scraper "A…" used to
        // stay above an English dub from scraper "Z…"), so the English key is
        // primary here and sourceName only orders within a language tier.
        sortedWith(
            compareBy<StreamItem> { stream -> if (AdaptiveStreamPicker.hasEnglishAudio(stream)) 0 else 1 }
                .thenBy { it.sourceName.orEmpty().lowercase() }
                .then(seedersThenLabel),
        )
    } else {
        // Preference off: ordering is exactly what it has always been —
        // scraper sourceName groups first, then seeders/label.
        sortedWith(
            compareBy<StreamItem> { it.sourceName.orEmpty().lowercase() }
                .then(seedersThenLabel),
        )
    }
}

/**
 * Full dub-first display sort for a whole group (used by the embedded-streams
 * path, which publishes a group without any other ordering step). Returns the
 * group untouched when the English-audio preference is off, so the original
 * order is preserved byte-for-byte in that case.
 */
internal fun AddonStreamGroup.sortedDubFirstForDisplay(
    preferEnglishAudio: Boolean,
): AddonStreamGroup {
    if (!preferEnglishAudio || streams.size < 2) return this
    return copy(streams = streams.sortedForGroupedDisplay(preferEnglishAudio = true))
}

/**
 * Stable English-audio-first partition of an already-presented group. Debrid
 * presentation regroups a group debrid-block-first (and custom debrid sort
 * criteria know nothing about language), which undoes the dub-first sort
 * applied earlier in the pipeline; running this as the final step restores
 * English-first across the debrid/passthrough blocks while preserving the
 * presented order inside each language tier. Returns the group untouched when
 * the English-audio preference is off.
 */
internal fun AddonStreamGroup.partitionedDubFirstForDisplay(
    preferEnglishAudio: Boolean,
): AddonStreamGroup {
    if (!preferEnglishAudio || streams.size < 2) return this
    val (english, other) = streams.partition { AdaptiveStreamPicker.hasEnglishAudio(it) }
    if (english.isEmpty() || other.isEmpty()) return this
    return copy(streams = english + other)
}

private fun String.fallbackRepositoryLabel(): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank {
            runBlocking { getString(Res.string.streams_plugin_repository_fallback) }
        }
    }
}
