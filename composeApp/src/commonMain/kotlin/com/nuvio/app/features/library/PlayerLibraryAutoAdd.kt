package com.nuvio.app.features.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.activePlaybackIdentity
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemTypeAliases
import com.nuvio.app.features.watching.domain.isSeriesLikeWatchingContentType
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.launch

private val log = Logger.withTag("PlayerLibraryAutoAdd")

/**
 * Auto-add to library on playback start (user request):
 * - Movies: added as soon as playback starts.
 * - Series: added only once the user starts a 2nd distinct episode, i.e. another
 *   episode of the same series was started (watch progress) or watched (watched mark;
 *   progress is cleared when an episode is marked watched) before. Never on the
 *   very first episode.
 *
 * Uses the same add path as the details-screen + button
 * ([LibraryRepository.toggleSaved]), guarded by [LibraryRepository.isSaved] so an
 * already-saved item is never removed. One-shot per playback item via
 * [PlayerScreenRuntime.libraryAutoAddEvaluatedKey].
 */
internal fun PlayerScreenRuntime.maybeAutoAddToLibrary() {
    val identityKey = "${activePlaybackIdentity}::${activeVideoId.orEmpty()}"
    if (libraryAutoAddEvaluatedKey == identityKey) return
    libraryAutoAddEvaluatedKey = identityKey

    val itemId = parentMetaId.trim()
    val itemType = parentMetaType.trim()
    if (itemId.isEmpty() || itemType.isEmpty()) return

    val shouldAdd = if (itemType.isSeriesLikeWatchingContentType()) {
        hasOtherEpisodeHistory(itemId)
    } else {
        true
    }
    if (!shouldAdd) return

    val item = LibraryItem(
        id = itemId,
        type = itemType,
        name = title,
        poster = poster,
        banner = background,
        logo = logo,
        imdbId = itemId.takeIf { it.startsWith("tt") },
        savedAtEpochMs = 0L,
    )
    // Same call pattern as the details-screen + button (Main scope; the
    // repository handles its own threading).
    scope.launch {
        runCatching {
            if (!LibraryRepository.isSaved(item.id, item.type)) {
                LibraryRepository.toggleSaved(item)
            }
        }.onFailure { error ->
            log.w(error) { "Auto-add to library failed id=${item.id} type=${item.type}" }
        }
    }
}

/** True when a different episode of [seriesId] was started or watched before. */
private fun PlayerScreenRuntime.hasOtherEpisodeHistory(seriesId: String): Boolean {
    WatchProgressRepository.ensureLoaded()
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val progressHit = WatchProgressRepository.uiState.value.entries.any { entry ->
        entry.parentMetaId.trim() == seriesId &&
            entry.seasonNumber != null &&
            entry.episodeNumber != null &&
            !(entry.seasonNumber == season && entry.episodeNumber == episode)
    }
    if (progressHit) return true

    // Progress is cleared when an episode is marked watched, so also check watched marks.
    if (season == null || episode == null) return false
    WatchedRepository.ensureLoaded()
    val watchedKeys = WatchedRepository.uiState.value.watchedKeys
    // Watched keys look like "type:id:season:episode"; the type may be an alias.
    return watchedItemTypeAliases(parentMetaType).any { alias ->
        val prefix = "$alias:$seriesId:"
        watchedKeys.any { key ->
            if (!key.startsWith(prefix)) return@any false
            val rest = key.removePrefix(prefix).split(":")
            if (rest.size != 2) return@any false
            val keySeason = rest[0].toIntOrNull() ?: return@any false
            val keyEpisode = rest[1].toIntOrNull() ?: return@any false
            keySeason != season || keyEpisode != episode
        }
    }
}
