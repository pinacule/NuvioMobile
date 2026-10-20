package com.nuvio.app.features.downloads

/**
 * How many episodes behind the one being watched auto-cleanup keeps: the
 * previous episode always survives, so the user can step back one episode.
 */
internal const val AUTO_CLEANUP_EPISODES_KEPT_BEHIND = 1

/**
 * Picks the downloads to remove when playback of [started] begins: fully
 * downloaded episodes of the same series and season numbered at least two
 * behind [started] (episodeNumber <= started - 2). The started episode and
 * the one right before it are always kept, and anything not [DownloadStatus.Completed]
 * (downloading, paused, failed) is never touched.
 */
internal fun episodesToAutoCleanup(
    items: List<DownloadItem>,
    started: DownloadItem,
): List<DownloadItem> {
    if (!started.isEpisode || started.status != DownloadStatus.Completed) return emptyList()
    val startedSeason = started.seasonNumber ?: return emptyList()
    val startedEpisode = started.episodeNumber ?: return emptyList()
    val newestCleanedEpisode = startedEpisode - AUTO_CLEANUP_EPISODES_KEPT_BEHIND - 1
    if (newestCleanedEpisode < 1) return emptyList()
    val seriesKey = started.parentMetaId.trim()
    return items
        .filter { item ->
            item.id != started.id &&
                item.status == DownloadStatus.Completed &&
                item.parentMetaId.trim() == seriesKey &&
                item.seasonNumber == startedSeason &&
                (item.episodeNumber ?: Int.MAX_VALUE) <= newestCleanedEpisode
        }
        .sortedWith(downloadSeriesEpisodeComparator)
}
