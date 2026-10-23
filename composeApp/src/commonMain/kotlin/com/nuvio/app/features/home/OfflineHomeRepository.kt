package com.nuvio.app.features.home

import com.nuvio.app.core.device.DeviceEnvironment
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.sortedForSeriesDownloads
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Transport classes the offline-home default is derived from. Sampled from the
 * same mechanisms the rest of the app already uses: [DeviceEnvironment]
 * transport snapshots (adaptive stream picking) and [NetworkStatusRepository]
 * reachability probes.
 */
enum class OfflineHomeTransport {
    Wifi,
    Cellular,
    Offline,
    Unknown,
}

data class OfflineHomeUiState(
    val transport: OfflineHomeTransport = OfflineHomeTransport.Unknown,
    val manualOverride: Boolean? = null,
    val isActive: Boolean = false,
)

/** Default per spec: offline home on fully-offline and on mobile data, not on Wi-Fi. */
internal fun offlineHomeDefaultActive(transport: OfflineHomeTransport): Boolean =
    transport == OfflineHomeTransport.Offline || transport == OfflineHomeTransport.Cellular

internal fun resolveOfflineHomeActive(
    transport: OfflineHomeTransport,
    manualOverride: Boolean?,
): Boolean = manualOverride ?: offlineHomeDefaultActive(transport)

/**
 * Classifies the current transport for the offline-home default.
 *
 * A present radio transport always wins over the reachability probe: the
 * probe reports one unconfirmed failure round during tunnels and dead spots
 * while the radio is still Wi-Fi/cellular, and letting that flip the class
 * would clear the user's manual override (see [nextOfflineHomeOverride]) and
 * silently undo their toggle. The probe's [NetworkCondition.NoInternet] only
 * yields [OfflineHomeTransport.Offline] when neither radio reports a
 * transport (airplane mode / no active network). Accepted trade-off: Wi-Fi
 * associated to a dead access point samples as Wifi, not Offline.
 */
internal fun classifyOfflineHomeTransport(
    isWifi: Boolean,
    isCellular: Boolean,
    condition: NetworkCondition,
): OfflineHomeTransport = when {
    isWifi -> OfflineHomeTransport.Wifi
    isCellular -> OfflineHomeTransport.Cellular
    // Only NoInternet with no radio means Offline. ServersUnreachable means
    // the internet probe succeeded and only the backend failed — the device
    // is not offline, so it falls through to Unknown.
    condition == NetworkCondition.NoInternet -> OfflineHomeTransport.Offline
    else -> OfflineHomeTransport.Unknown
}

/**
 * The manual override is a session-level escape for the *current* conditions:
 * when the transport class changes, the override is dropped and the default
 * for the new conditions applies again.
 */
internal fun nextOfflineHomeOverride(
    currentOverride: Boolean?,
    previousTransport: OfflineHomeTransport,
    nextTransport: OfflineHomeTransport,
): Boolean? = if (nextTransport != previousTransport) null else currentOverride

/**
 * Confirmation state for transport samples. A single instantaneous sample
 * is unreliable: during handover gaps Android momentarily reports no
 * transport, sampling as Offline/Unknown for one round, and committing
 * that would clear the user's manual override (via
 * [nextOfflineHomeOverride]) and flip the homepage unasked.
 *
 * Rules: the very first sample (no confirmed class yet) commits
 * immediately; after that, a new class commits only when the SAME class
 * is seen in two consecutive samples; a sample equal to the confirmed
 * class clears any pending change; an Unknown sample never commits and
 * breaks any pending run (it is indeterminate, not a class change).
 */
internal data class ConfirmedTransportState(
    val confirmed: OfflineHomeTransport? = null,
    val pendingClass: OfflineHomeTransport? = null,
    val pendingCount: Int = 0,
)

internal fun confirmedTransport(
    state: ConfirmedTransportState,
    sample: OfflineHomeTransport,
): ConfirmedTransportState = when {
    state.confirmed == null -> ConfirmedTransportState(confirmed = sample)
    sample == state.confirmed ->
        state.copy(pendingClass = null, pendingCount = 0)
    sample == OfflineHomeTransport.Unknown ->
        state.copy(pendingClass = null, pendingCount = 0)
    sample == state.pendingClass -> {
        val count = state.pendingCount + 1
        if (count >= 2) {
            ConfirmedTransportState(confirmed = sample)
        } else {
            state.copy(pendingCount = count)
        }
    }
    else -> state.copy(pendingClass = sample, pendingCount = 1)
}

/**
 * Session-only state for the offline homepage mode.
 *
 * Effective mode = manual override (set by the Library toggle) ?: connectivity
 * default. Nothing is persisted: the automatic behavior never depends on a
 * stored setting, and an override never survives a transport change or an app
 * restart.
 */
object OfflineHomeRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The startup sample commits immediately (empty state); later class
    // changes need confirmation — see ConfirmedTransportState.
    private var transportConfirmation: ConfirmedTransportState =
        confirmedTransport(ConfirmedTransportState(), sampleTransport())

    private val transport: OfflineHomeTransport
        get() = transportConfirmation.confirmed ?: OfflineHomeTransport.Unknown

    private var manualOverride: Boolean? = null

    private val _uiState = MutableStateFlow(currentState())
    val uiState: StateFlow<OfflineHomeUiState> = _uiState.asStateFlow()

    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            // Reachability changes re-sample the transport (and thereby
            // re-evaluate the default) without inventing a second
            // connectivity mechanism.
            NetworkStatusRepository.uiState.collect { refresh() }
        }
        refresh()
    }

    /**
     * Re-samples the transport through the confirmation helper; only a
     * *confirmed* class change clears the manual override (a transient
     * single-sample flap keeps both the class and the override).
     */
    fun refresh() {
        val previousTransport = transport
        transportConfirmation = confirmedTransport(transportConfirmation, sampleTransport())
        manualOverride = nextOfflineHomeOverride(manualOverride, previousTransport, transport)
        _uiState.value = currentState()
    }

    /** Toggle from the Library filter row: flips the *effective* mode for now. */
    fun toggle() {
        manualOverride = !_uiState.value.isActive
        _uiState.value = currentState()
    }

    private fun currentState(): OfflineHomeUiState = OfflineHomeUiState(
        transport = transport,
        manualOverride = manualOverride,
        isActive = resolveOfflineHomeActive(transport, manualOverride),
    )

    private fun sampleTransport(): OfflineHomeTransport = classifyOfflineHomeTransport(
        isWifi = DeviceEnvironment.isWifi(),
        isCellular = DeviceEnvironment.isCellular(),
        condition = NetworkStatusRepository.uiState.value.condition,
    )
}

/**
 * The downloads-only Continue Watching for the offline homepage: the normal
 * CW items whose target video is actually on disk as a completed download.
 * Items keep their resume position — playback goes through the exact same
 * click path as the online row.
 */
internal fun filterContinueWatchingForOfflineHome(
    items: List<ContinueWatchingItem>,
    downloads: List<DownloadItem>,
): List<ContinueWatchingItem> {
    // Callers pass DownloadsRepository.playableLocalDownloads() (disk-
    // verified). Do NOT re-filter with DownloadItem.isPlayable here: that
    // weaker check requires a non-blank stored localFileUri, so a download
    // verified on disk via the file-name fallback (blank stored URI) would
    // wrongly vanish from offline CW/tiles.
    if (downloads.isEmpty()) return emptyList()
    val downloadedEpisodes = downloads.mapNotNullTo(mutableSetOf()) { download ->
        val season = download.seasonNumber
        val episode = download.episodeNumber
        if (season == null || episode == null) {
            null
        } else {
            Triple(download.parentMetaId.trim(), season, episode)
        }
    }
    // Movie downloads only: an episode download must never validate a
    // content-level match for its show.
    val downloadedMovieContentIds = downloads
        .filter { download ->
            download.seasonNumber == null && download.episodeNumber == null &&
                download.parentMetaType.equals("movie", ignoreCase = true)
        }
        .mapTo(mutableSetOf()) { download -> download.parentMetaId.trim() }

    return items.filter { item ->
        val season = item.seasonNumber
        val episode = item.episodeNumber
        when {
            season != null && episode != null ->
                Triple(item.parentMetaId.trim(), season, episode) in downloadedEpisodes
            // Only a genuine movie item (movie type, no episode coordinates)
            // may match on the content alone, and only against a movie
            // download of that content. An episode item with a missing
            // coordinate is not a movie and must not pass just because part
            // of its show is downloaded.
            season == null && episode == null && item.parentMetaType.equals("movie", ignoreCase = true) ->
                item.parentMetaId.trim() in downloadedMovieContentIds
            else -> false
        }
    }
}

/** One poster tile on the offline homepage: a downloaded show or movie. */
internal data class OfflineHomeDownloadTile(
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val poster: String?,
    /** The concrete download that plays when the tile is tapped. */
    val download: DownloadItem,
) {
    val key: String
        get() = "$parentMetaType:$parentMetaId"

    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = parentMetaId,
        type = parentMetaType,
        name = title,
        poster = poster,
        rawPosterUrl = poster,
    )
}

/**
 * Groups playable downloads into one tile per show/movie. The tile's playable
 * download is the Continue Watching target for that content when there is one
 * (so a tap continues where the user left off; [openDownloadedItem] applies
 * the saved resume position for that video), otherwise the first episode in
 * season order. Tiles are ordered by most recent download activity.
 */
internal fun buildOfflineHomeDownloadTiles(
    downloads: List<DownloadItem>,
    offlineContinueWatchingItems: List<ContinueWatchingItem>,
): List<OfflineHomeDownloadTile> {
    // See filterContinueWatchingForOfflineHome: inputs are already disk-
    // verified by the caller; no isPlayable re-filter here.
    if (downloads.isEmpty()) return emptyList()

    // One entry per logical video: re-downloads of the same episode collapse
    // to the most recently updated copy.
    val deduped = downloads
        .groupBy { it.logicalContentKey }
        .values
        .map { copies -> copies.maxByOrNull { it.updatedAtEpochMs } ?: copies.first() }

    val continueWatchingByContentId = offlineContinueWatchingItems
        .groupBy { it.parentMetaId.trim() to it.parentMetaType }
        .mapValues { (_, items) -> items.first() }

    return deduped
        .groupBy { it.parentMetaId.trim() to it.parentMetaType }
        .map { (contentKey, contentDownloads) ->
            val (parentMetaId, parentMetaType) = contentKey
            val continueWatchingItem = continueWatchingByContentId[contentKey]
            val representative = when {
                continueWatchingItem == null -> contentDownloads.sortedForSeriesDownloads().first()
                continueWatchingItem.seasonNumber != null && continueWatchingItem.episodeNumber != null ->
                    contentDownloads.firstOrNull { download ->
                        download.seasonNumber == continueWatchingItem.seasonNumber &&
                            download.episodeNumber == continueWatchingItem.episodeNumber
                    } ?: contentDownloads.sortedForSeriesDownloads().first()
                else -> contentDownloads.firstOrNull { !it.isEpisode }
                    ?: contentDownloads.sortedForSeriesDownloads().first()
            }
            OfflineHomeDownloadTile(
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                title = representative.title,
                poster = contentDownloads.firstNotNullOfOrNull { download ->
                    download.poster?.takeIf { it.isNotBlank() }
                },
                download = representative,
            ) to contentDownloads.maxOf { it.updatedAtEpochMs }
        }
        .sortedByDescending { (_, latestActivity) -> latestActivity }
        .map { (tile, _) -> tile }
}
