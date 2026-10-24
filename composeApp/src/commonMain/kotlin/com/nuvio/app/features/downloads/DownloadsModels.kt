package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_p2p_disabled
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import org.jetbrains.compose.resources.getString

@Serializable
enum class DownloadSourceKind {
    Http,
    Torrent,
}

@Serializable
enum class DownloadStatus {
    Downloading,
    Paused,
    Completed,
    Failed,
}

@Serializable
data class DownloadItem(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    /** Quality the stream was picked at (e.g. "720p"): used to re-pick a fresh URL when the link dies. */
    val downloadQuality: String? = null,
    val sourceUrl: String,
    val sourceKind: DownloadSourceKind = DownloadSourceKind.Http,
    val torrentInfoHash: String? = null,
    val torrentFileIdx: Int? = null,
    val torrentTrackers: List<String> = emptyList(),
    val torrentFilenameHint: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val subtitleRequests: List<SubtitleAddonRequest> = emptyList(),
    val sourceSubtitles: List<StreamSubtitle> = emptyList(),
    val localFileUri: String? = null,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * Parked by the Wi-Fi-only gate ([DownloadWifiGate]): the download was
     * paused because the device left Wi-Fi (or started while off Wi-Fi) and
     * auto-resumes when Wi-Fi returns. A plain user pause never sets this
     * flag (pausing clears it), so the watcher never auto-resumes something
     * the user stopped on purpose — except when the user then taps Resume
     * while off Wi-Fi: that parks the item as waiting, and it auto-resumes
     * on Wi-Fi return, because the user just asked it to run. Persisted
     * with the item (both payload codecs tolerate the new field: missing
     * in old payloads decodes to false).
     */
    val waitingForWifi: Boolean = false,
    /** When [sourceUrl] was last (re-)resolved; null means at creation ([createdAtEpochMs]). */
    val sourceResolvedAtEpochMs: Long? = null,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isTorrent: Boolean
        get() = sourceKind == DownloadSourceKind.Torrent

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank()

    val displaySubtitle: String
        get() = episodeTitle.orEmpty()

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = if (isEpisode) {
            "${parentMetaId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"
        } else {
            "${parentMetaId.trim()}|movie"
        }
}

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
) {
    val activeItems: List<DownloadItem>
        get() = items.filter { it.status != DownloadStatus.Completed }

    val completedItems: List<DownloadItem>
        get() = items.filter { it.status == DownloadStatus.Completed }
}

enum class DownloadEnqueueResult {
    Started,
    Replaced,
    MissingUrl,
    UnsupportedFormat,
    P2pDisabled;

    fun toastMessage(): String = runBlocking {
        when (this@DownloadEnqueueResult) {
            Started -> getString(Res.string.downloads_enqueue_started)
            Replaced -> getString(Res.string.downloads_enqueue_replaced)
            MissingUrl -> getString(Res.string.downloads_enqueue_missing_url)
            UnsupportedFormat -> getString(Res.string.downloads_enqueue_unsupported_format)
            P2pDisabled -> getString(Res.string.downloads_enqueue_p2p_disabled)
        }
    }
}

/** Watched state + in-progress entry for one completed download. */
internal data class DownloadWatchState(
    val progressEntry: WatchProgressEntry?,
    val isWatched: Boolean,
) {
    /** The entry worth drawing a progress bar for — the episode grid's visible-entry rule. */
    val visibleProgressEntry: WatchProgressEntry?
        get() = progressEntry?.takeIf { it.durationMs > 0L && !it.isCompleted }
}

/**
 * The completed downloads whose already-computed watch state says they were
 * watched. Active downloads are excluded even if a caller passes the full
 * item list instead of only [DownloadsUiState.completedItems].
 */
internal fun watchedCompletedDownloads(
    items: List<DownloadItem>,
    watchStates: Map<String, DownloadWatchState>,
): List<DownloadItem> = items.filter { item ->
    item.status == DownloadStatus.Completed && watchStates[item.id]?.isWatched == true
}

internal fun List<DownloadItem>.sortedForSeriesDownloads(): List<DownloadItem> =
    sortedWith(downloadSeriesEpisodeComparator)

/**
 * Series-episode order, scoped PER SERIES: a show's own title/parent first,
 * then season, then episode. The old global season/episode ordering let
 * every S01E01 (preloads, other shows) jump ahead of the show the user
 * actually queued, and movies (no season) sorted behind every episode of
 * every show. Display sorting shares this order, so each series' episodes
 * stay contiguous there too.
 */
internal val downloadSeriesEpisodeComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.title.trim().lowercase() }
        .thenBy { it.parentMetaId.trim().lowercase() }
        .thenBy { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeTitle?.trim().orEmpty().lowercase() }
        .thenBy { it.id }
