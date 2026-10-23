package com.nuvio.app.features.library

import com.nuvio.app.features.downloads.DownloadItem

/**
 * Downloads-only Library for offline-home mode: keeps only the library items
 * that have at least one playable local download, and drops sections left
 * empty by the filter. The type filter and sort controls keep working on top
 * of the result because they consume the projected sections downstream.
 *
 * [downloads] must already be verified playable from disk
 * ([com.nuvio.app.features.downloads.DownloadsRepository.playableLocalDownloads])
 * — the same input the offline homepage consumes. A completed download record
 * whose file was deleted outside the app never reaches this function, so it
 * cannot keep an item visible.
 *
 * Matching follows the offline-home conventions (see
 * filterContinueWatchingForOfflineHome): ids are compared trimmed, and the
 * two download kinds validate different item kinds — an episode download
 * (season and episode set) only validates its series, a movie download (no
 * coordinates at all, parent type "movie") only validates a movie item. An
 * episode record must never keep a movie visible just because the ids
 * coincide, and vice versa.
 */
internal fun filterLibrarySectionsForOfflineHome(
    sections: List<LibrarySection>,
    downloads: List<DownloadItem>,
): List<LibrarySection> {
    if (downloads.isEmpty()) return emptyList()
    val downloadedSeriesIds = downloads
        .filter { download -> download.seasonNumber != null && download.episodeNumber != null }
        .mapTo(mutableSetOf()) { download -> download.parentMetaId.trim() }
    val downloadedMovieIds = downloads
        .filter { download ->
            download.seasonNumber == null && download.episodeNumber == null &&
                download.parentMetaType.equals("movie", ignoreCase = true)
        }
        .mapTo(mutableSetOf()) { download -> download.parentMetaId.trim() }
    if (downloadedSeriesIds.isEmpty() && downloadedMovieIds.isEmpty()) return emptyList()

    return sections.mapNotNull { section ->
        val keptItems = section.items.filter { item ->
            val id = item.id.trim()
            if (item.type.equals("movie", ignoreCase = true)) {
                id in downloadedMovieIds
            } else {
                id in downloadedSeriesIds
            }
        }
        if (keptItems.isEmpty()) null else section.copy(items = keptItems)
    }
}
