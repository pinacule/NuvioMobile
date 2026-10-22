package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioAnimatedWatchedBadge
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.rememberEpisodeImageModel
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }

    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    // Watched/progress display state per completed download. Playback already records
    // into these stores for downloaded playback too; the screen just never read them.
    val completedWatchStates = remember(
        uiState.completedItems,
        watchProgressUiState.entries,
        watchedUiState.watchedKeys,
    ) {
        uiState.completedItems.associate { item ->
            item.id to item.downloadWatchState(watchedUiState.watchedKeys)
        }
    }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    val completedEpisodes = remember(uiState.items) {
        uiState.completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                },
            )
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                watchStates = completedWatchStates,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDeleteDownload = { downloadPendingDeletionId = it },
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                watchStates = completedWatchStates,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
            )
        }
    }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    watchStates: Map<String, DownloadWatchState>,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    // Displayed in queue order (first episode first), matching the order transfer slots are
    // handed out in.
    val activeItems = uiState.activeItems.sortedForSeriesDownloads()
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = uiState.completedItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) -> episodes.firstOrNull()?.let { first -> first to episodes } }
        .sortedBy { (item, _) -> item.title.lowercase() }

    if (uiState.items.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    item { DownloadsSummary(uiState) }

    if (activeItems.isNotEmpty()) {
        item { SectionTitle(stringResource(Res.string.downloads_section_active)) }
        items(items = activeItems, key = { it.id }) { item ->
            ActiveDownloadCard(
                item = item,
                onPlay = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item { SectionTitle(stringResource(Res.string.downloads_section_shows)) }
        items(items = completedShows, key = { (item, _) -> item.parentMetaId }) { (item, episodes) ->
            val watchedCount = episodes.count { episode -> watchStates[episode.id]?.isWatched == true }
            val allWatched = episodes.isNotEmpty() && watchedCount == episodes.size
            LibraryCard(
                artwork = item.poster ?: item.background,
                title = item.title,
                detail = listOfNotNull(
                    stringResource(
                        Res.string.downloads_show_summary,
                        episodes.size,
                        formatBytes(episodes.sumOf { it.sizeOnDisk() }),
                    ),
                    when {
                        allWatched -> "All watched"
                        watchedCount > 0 -> "$watchedCount of ${episodes.size} watched"
                        else -> null
                    },
                ).joinToString(" · "),
                showWatchedBadge = allWatched,
                onClick = { onOpenShow(item.parentMetaId, item.title) },
                trailing = {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    if (completedMovies.isNotEmpty()) {
        item { SectionTitle(stringResource(Res.string.downloads_section_movies)) }
        items(items = completedMovies, key = { it.id }) { item ->
            val watchState = watchStates[item.id]
            LibraryCard(
                artwork = item.poster ?: item.background,
                title = item.title,
                detail = formatBytes(item.sizeOnDisk()),
                showWatchedBadge = watchState?.isWatched == true,
                progressEntry = watchState?.visibleProgressEntry,
                onClick = { onOpenDownload(item) },
                trailing = { CompletedActions(onPlay = { onOpenDownload(item) }, onDelete = { onDeleteDownload(item.id) }) },
            )
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    watchStates: Map<String, DownloadWatchState>,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    val seasons = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) -> if (season == 0) 0 else 1 }
                .thenBy { (season, _) -> season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item(key = "season-$seasonNumber") {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }
        items(items = entries.sortedForSeriesDownloads(), key = { it.id }) { item ->
            EpisodeCard(
                item = item,
                watchState = watchStates[item.id],
                onPlay = { onOpenDownload(item) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }
}

/** One line on what is happening and how much space downloads take. */
@Composable
private fun DownloadsSummary(uiState: DownloadsUiState) {
    val downloading = uiState.items.count { it.status == DownloadStatus.Downloading }
    val usedBytes = uiState.items.sumOf { it.sizeOnDisk() }
    val parts = buildList {
        if (downloading > 0) add(stringResource(Res.string.downloads_summary_active, downloading))
        add(stringResource(Res.string.downloads_summary_storage, formatBytes(usedBytes)))
    }
    Text(
        text = parts.joinToString(" · "),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActiveDownloadCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val swarms by TorrentDownloadSwarmRepository.state.collectAsStateWithLifecycle()
    val queuedHttpIds by DownloadQueueState.queued.collectAsStateWithLifecycle()
    val p2pSettings by P2pSettingsRepository.uiState.collectAsStateWithLifecycle()
    val swarm = swarms[item.id]?.takeIf { item.isTorrent && item.status == DownloadStatus.Downloading }
    val estimatedSpeed = rememberTransferSpeed(item)
    val speed = swarm?.downloadSpeedBytesPerSecond ?: estimatedSpeed
    val queued = swarm?.phase == TorrentDownloadPhase.Queued ||
        (!item.isTorrent && item.status == DownloadStatus.Downloading && item.id in queuedHttpIds)
    // In-progress torrents can play progressively from the partial data; the tap handler routes
    // them through the P2P engine, which reuses the download's torrent.
    val canWatchNow = item.isTorrent &&
        !item.torrentInfoHash.isNullOrBlank() &&
        (item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused) &&
        p2pSettings.p2pEnabled

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = canWatchNow, onClick = onPlay),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Artwork(
                        url = item.episodeThumbnail,
                        fallbackUrl = item.background ?: item.poster,
                        width = 112.dp,
                        aspectRatio = 16f / 9f,
                    )
                    if (canWatchNow) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(Res.string.action_play),
                            tint = Color.White,
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                                .padding(8.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    StatusLabel(item = item, queued = queued)
                    Text(
                        text = item.displayTitle(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = downloadDisplaySubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (item.status) {
                    DownloadStatus.Downloading -> ActionIcon(Icons.Rounded.Pause, Res.string.compose_action_pause, onPause)
                    DownloadStatus.Paused -> ActionIcon(Icons.Rounded.PlayArrow, Res.string.action_resume, onResume)
                    DownloadStatus.Failed -> ActionIcon(Icons.Rounded.Refresh, Res.string.action_retry, onRetry)
                    DownloadStatus.Completed -> Unit
                }
                ActionIcon(Icons.Rounded.Delete, Res.string.action_delete, onDelete)
            }

            val total = item.totalBytes?.takeIf { it > 0L }
            if (item.status == DownloadStatus.Downloading && total == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    strokeCap = StrokeCap.Round,
                    color = if (item.status == DownloadStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            Text(
                text = progressLine(item, total, speed.takeIf { item.status == DownloadStatus.Downloading && !queued }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            torrentSwarmText(item, swarm, p2pSettings.hideTorrentStats)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.status == DownloadStatus.Failed) {
                Text(
                    text = item.errorMessage ?: stringResource(Res.string.downloads_status_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusLabel(item: DownloadItem, queued: Boolean) {
    val (text, color) = when {
        queued -> stringResource(Res.string.downloads_label_queued) to MaterialTheme.colorScheme.onSurfaceVariant
        item.status == DownloadStatus.Downloading ->
            stringResource(Res.string.downloads_label_downloading) to MaterialTheme.colorScheme.primary
        item.status == DownloadStatus.Paused ->
            stringResource(Res.string.downloads_label_paused) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(Res.string.downloads_status_failed) to MaterialTheme.colorScheme.error
    }
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

/** "45% · 540 MB of 1.2 GB · 8 min left" — time left only when the speed is known. */
@Composable
private fun progressLine(item: DownloadItem, total: Long?, speed: Long?): String {
    val downloaded = formatBytes(item.downloadedBytes)
    val sizeText = if (total != null) {
        val percent = "${(item.progressFraction * 100f).toInt()}%"
        stringResource(Res.string.downloads_progress_size, percent, downloaded, formatBytes(total))
    } else {
        downloaded
    }
    if (speed == null || speed <= 0L || total == null) return sizeText
    val withSpeed = "$sizeText · ${formatP2pSpeed(speed)}"
    val secondsLeft = (total - item.downloadedBytes).coerceAtLeast(0L) / speed
    return when {
        secondsLeft < 60L -> stringResource(Res.string.downloads_eta_under_minute, withSpeed)
        secondsLeft < 3_600L -> stringResource(Res.string.downloads_eta_minutes, withSpeed, (secondsLeft / 60L).toInt())
        else -> stringResource(
            Res.string.downloads_eta_hours,
            withSpeed,
            (secondsLeft / 3_600L).toInt(),
            ((secondsLeft % 3_600L) / 60L).toInt(),
        )
    }
}

/** Swarm line for running torrents: queued, finding peers, or the current speed. Peer and seed
 * counts are deliberately not shown: the engine only reports them globally (across every torrent
 * it serves), so per-episode counts next to a per-episode speed would be misleading. */
@Composable
private fun torrentSwarmText(item: DownloadItem, swarm: TorrentDownloadSwarm?, hideStats: Boolean): String? {
    val findingPeersText = stringResource(Res.string.downloads_torrent_finding_peers)
    val queuedText = stringResource(Res.string.downloads_torrent_queued)
    if (!item.isTorrent || item.status != DownloadStatus.Downloading || swarm == null) return null
    return when (swarm.phase) {
        TorrentDownloadPhase.Queued -> queuedText
        TorrentDownloadPhase.FindingPeers -> findingPeersText
        TorrentDownloadPhase.Downloading ->
            if (hideStats) null else formatP2pSpeed(swarm.downloadSpeedBytesPerSecond)
    }
}

/**
 * Smoothed bytes-per-second from successive progress updates, for downloads that do not report a
 * speed themselves.
 */
@Composable
private fun rememberTransferSpeed(item: DownloadItem): Long? {
    var lastSample by remember(item.id) { mutableStateOf<Pair<Long, Long>?>(null) }
    var speed by remember(item.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(item.id, item.downloadedBytes, item.status) {
        if (item.status != DownloadStatus.Downloading) {
            lastSample = null
            speed = null
            return@LaunchedEffect
        }
        val now = DownloadsClock.nowEpochMs()
        val previous = lastSample
        if (previous != null && now > previous.second && item.downloadedBytes >= previous.first) {
            val instant = (item.downloadedBytes - previous.first) * 1_000L / (now - previous.second)
            speed = speed?.let { (it * 0.7 + instant * 0.3).toLong() } ?: instant
        }
        lastSample = item.downloadedBytes to now
    }
    return speed
}

@Composable
private fun EpisodeCard(
    item: DownloadItem,
    watchState: DownloadWatchState?,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(enabled = item.isPlayable, onClick = onPlay),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                Artwork(
                    url = item.episodeThumbnail,
                    fallbackUrl = item.background ?: item.poster,
                    width = 120.dp,
                    aspectRatio = 16f / 9f,
                )
                // Same watched language as the episode grid: badge top-end over the
                // artwork, progress bar along its bottom edge.
                NuvioAnimatedWatchedBadge(
                    isVisible = watchState?.isWatched == true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
                watchState?.visibleProgressEntry?.let { entry ->
                    NuvioProgressBar(
                        progress = entry.progressFraction,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                        height = 3.dp,
                        trackColor = Color.White.copy(alpha = 0.22f),
                        fillColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.episodeNumber?.let { episode ->
                        stringResource(Res.string.downloads_episode_label, episode, item.displayTitle())
                    } ?: item.displayTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatBytes(item.sizeOnDisk()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CompletedActions(onPlay = onPlay, onDelete = onDelete)
        }
    }
}

@Composable
private fun LibraryCard(
    artwork: String?,
    title: String,
    detail: String,
    onClick: () -> Unit,
    showWatchedBadge: Boolean = false,
    progressEntry: WatchProgressEntry? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                Artwork(url = artwork, width = 56.dp, aspectRatio = 2f / 3f)
                NuvioAnimatedWatchedBadge(
                    isVisible = showWatchedBadge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                )
                progressEntry?.let { entry ->
                    NuvioProgressBar(
                        progress = entry.progressFraction,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                        height = 3.dp,
                        trackColor = Color.White.copy(alpha = 0.22f),
                        fillColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun CompletedActions(onPlay: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(Icons.Rounded.PlayArrow, Res.string.action_play, onPlay)
        ActionIcon(Icons.Rounded.Delete, Res.string.action_delete, onDelete)
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: org.jetbrains.compose.resources.StringResource,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = stringResource(description))
    }
}

@Composable
private fun Artwork(url: String?, fallbackUrl: String? = null, tmdbStillUrl: String? = null, width: Dp, aspectRatio: Float) {
    // Dead episode-still URLs are non-null, so a plain Elvis never engages: route the load
    // through the fallback interceptor so a 404 retries with the TMDB still (when available),
    // then the show art.
    val imageModel = rememberEpisodeImageModel(url, tmdbStillUrl, fallbackUrl)
    var loadFailed by remember(url, tmdbStillUrl, fallbackUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (imageModel != null && !loadFailed) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                onError = { loadFailed = true },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Watched state + in-progress entry for one completed download. */
private data class DownloadWatchState(
    val progressEntry: WatchProgressEntry?,
    val isWatched: Boolean,
) {
    /** The entry worth drawing a progress bar for — the episode grid's visible-entry rule. */
    val visibleProgressEntry: WatchProgressEntry?
        get() = progressEntry?.takeIf { it.durationMs > 0L && !it.isCompleted }
}

private fun DownloadItem.downloadWatchState(watchedKeys: Set<String>): DownloadWatchState {
    val entry = WatchProgressRepository.progressForVideo(
        videoId = videoId,
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    // Same watched rule as the episode grid (DetailSeriesContent): a completed
    // progress entry, or WatchingState's check over the published watched keys.
    // Unlike the bare WatchedRepository.isWatched this also resolves
    // franchise-parent anime (mal:/kitsu:) through the Simkl fallback when the
    // parent meta id differs from the Simkl entry id, so fallback-only marks
    // badge here exactly as they do in the grid. The MetaVideo is rebuilt from
    // the download's own ids — videoId is the episode's MetaVideo.id (see
    // SeasonDownloads) — and season/episode are null for movies, where the
    // check reduces to the same content-level key lookup as before (the
    // fallback only fires for a non-null episode number).
    val watched = entry?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
        watchedKeys = watchedKeys,
        metaType = parentMetaType,
        metaId = parentMetaId,
        episode = MetaVideo(
            id = videoId,
            title = title,
            season = seasonNumber,
            episode = episodeNumber,
        ),
    )
    return DownloadWatchState(progressEntry = entry, isWatched = watched)
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

private fun DownloadItem.sizeOnDisk(): Long =
    if (status == DownloadStatus.Completed) totalBytes ?: downloadedBytes else downloadedBytes

@Composable
private fun downloadDisplaySubtitle(item: DownloadItem): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.providerName
    }
    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(episodeCode, item.title.trim().takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" · ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
