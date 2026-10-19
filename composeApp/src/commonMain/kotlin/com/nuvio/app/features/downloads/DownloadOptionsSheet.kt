@file:OptIn(ExperimentalMaterial3Api::class)

package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.AdaptiveStreamPicker
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_options_close
import nuvio.composeapp.generated.resources.downloads_options_custom_downloaded
import nuvio.composeapp.generated.resources.downloads_options_custom_downloaded_count
import nuvio.composeapp.generated.resources.downloads_options_custom_nothing_selected
import nuvio.composeapp.generated.resources.downloads_options_custom_season
import nuvio.composeapp.generated.resources.downloads_options_custom_specials
import nuvio.composeapp.generated.resources.downloads_options_custom_unavailable
import nuvio.composeapp.generated.resources.downloads_options_custom_watched
import nuvio.composeapp.generated.resources.downloads_options_custom_whole_season
import nuvio.composeapp.generated.resources.downloads_options_custom_youre_here
import nuvio.composeapp.generated.resources.downloads_options_load_failed
import nuvio.composeapp.generated.resources.downloads_options_no_streams
import nuvio.composeapp.generated.resources.downloads_options_quality
import nuvio.composeapp.generated.resources.downloads_options_scope_custom
import nuvio.composeapp.generated.resources.downloads_options_scope_next_episodes
import nuvio.composeapp.generated.resources.downloads_options_scope_rest_of_season
import nuvio.composeapp.generated.resources.downloads_options_title
import nuvio.composeapp.generated.resources.hero_download
import nuvio.composeapp.generated.resources.season_download_no_episodes
import nuvio.composeapp.generated.resources.season_download_nothing_new
import nuvio.composeapp.generated.resources.season_download_queued
import nuvio.composeapp.generated.resources.season_download_queued_missing
import nuvio.composeapp.generated.resources.season_download_started
import nuvio.composeapp.generated.resources.season_download_unsupported
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Scope choices for the long-press poster download dialog (series only). */
/** Survives the coordinator sheet being dismissed so the enqueue finishes. */
private val posterDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

enum class PosterDownloadScope(val episodeCount: Int?) {    REST_OF_SEASON(null),
    NEXT_3(3),
    NEXT_5(5),
    NEXT_10(10),
}

data class PosterDownloadRequest(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val isSeries: Boolean,
)

private sealed interface PosterDownloadUiState {
    data object Loading : PosterDownloadUiState
    data object LoadFailed : PosterDownloadUiState
    data object NoStreams : PosterDownloadUiState
    data class Ready(
        val startVideo: MetaVideo,
        val allVideos: List<MetaVideo>,
        val groups: List<AddonStreams>,
        val qualities: List<String>,
        val selectedQuality: String,
        val selectedScope: PosterDownloadScope,
        val customMode: Boolean = false,
        val customSelected: Set<Pair<Int, Int>> = emptySet(),
    ) : PosterDownloadUiState
}

/**
 * Full long-press download flow: resolves the current episode, fetches streams
 * for the quality picker, then enqueues the chosen scope at the chosen quality.
 */
@Composable
fun PosterDownloadCoordinator(
    request: PosterDownloadRequest,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var uiState by remember(request) { mutableStateOf<PosterDownloadUiState>(PosterDownloadUiState.Loading) }
    var showP2pConsent by remember(request) { mutableStateOf(false) }
    var pendingEnqueue by remember(request) { mutableStateOf<(() -> Unit)?>(null) }

    val dismiss: () -> Unit = {
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    val loadFailedText = stringResource(Res.string.downloads_options_load_failed)
    val noStreamsText = stringResource(Res.string.downloads_options_no_streams)
    val noEpisodesText = stringResource(Res.string.season_download_no_episodes)
    val unsupportedText = stringResource(Res.string.season_download_unsupported)
    val nothingNewText = stringResource(Res.string.season_download_nothing_new)
    val startedText = stringResource(Res.string.season_download_started)
    val nothingSelectedText = stringResource(downloads_options_custom_nothing_selected)
    val unavailableSelectedText = stringResource(downloads_options_custom_unavailable)

    // Local functions are declared before their call sites (Kotlin requirement).
    fun enqueueReady(
        req: PosterDownloadRequest,
        ready: PosterDownloadUiState.Ready,
        onResult: suspend (SeasonDownloadResult) -> Unit,
    ) {
        // "Download next X" skips what's already saved: the smart window starts
        // after max(current episode, highest downloaded episode of the season).
        val isDownloaded: (Int, Int) -> Boolean = { season, episode ->
            DownloadsRepository.hasActiveOrCompletedDownload(req.parentMetaId, season, episode)
        }
        val targets = when {
            !req.isSeries -> listOf(ready.startVideo)
            ready.customMode ->
                EpisodeDownloadPlanner.explicitVideos(ready.allVideos, ready.customSelected)
            ready.selectedScope == PosterDownloadScope.REST_OF_SEASON ->
                EpisodeDownloadPlanner.restOfSeasonVideos(ready.allVideos, ready.startVideo, isDownloaded)
            else -> EpisodeDownloadPlanner.nextNVideos(
                ready.allVideos,
                ready.startVideo,
                ready.selectedScope.episodeCount ?: 3,
                isDownloaded,
            )
        }
        if (targets.isEmpty()) {
            if (req.isSeries && ready.customMode) {
                // Custom mode: distinguish "nothing checked" from "checked but unavailable".
                NuvioToastController.show(
                    if (ready.customSelected.isEmpty()) nothingSelectedText else unavailableSelectedText
                )
                return
            }
            // Empty because everything is already saved (not because there are
            // no episodes): keep the "already downloaded" toast for that case.
            val anythingToDownload = when {
                !req.isSeries -> true
                ready.selectedScope == PosterDownloadScope.REST_OF_SEASON ->
                    EpisodeDownloadPlanner.restOfSeasonVideos(ready.allVideos, ready.startVideo).isNotEmpty()
                else -> EpisodeDownloadPlanner.nextNVideos(
                    ready.allVideos,
                    ready.startVideo,
                    ready.selectedScope.episodeCount ?: 3,
                ).isNotEmpty()
            }
            NuvioToastController.show(if (anythingToDownload) nothingNewText else noEpisodesText)
            return
        }
        // Dub over sub, always: prefer an English-audio release at the chosen quality.
        runCatching { PlayerSettingsRepository.ensureLoaded() }
        val preferEnglishAudio = AdaptiveStreamPicker.shouldPreferEnglishAudio(
            PlayerSettingsRepository.uiState.value.preferredAudioLanguage,
        )
        val template = EpisodeDownloadPlanner.pickBestStreamAtQuality(
            ready.groups.flatMap { it.streams },
            ready.selectedQuality,
            preferEnglishAudio = preferEnglishAudio,
        )
        val manifest = ready.groups.firstOrNull { it.addonId == template?.addonId }?.manifest
        if (template == null || manifest == null) {
            NuvioToastController.show(noStreamsText)
            return
        }
        NuvioToastController.show(startedText)
        posterDownloadScope.launch {
            val result = SeasonDownloads.enqueueVideos(
                contentType = req.contentType,
                parentMetaId = req.parentMetaId,
                parentMetaType = req.parentMetaType,
                title = req.title,
                logo = req.logo,
                poster = req.poster,
                background = req.background,
                targets = targets,
                addon = manifest,
                template = template,
                templateVideoId = ready.startVideo.id,
            )
            onResult(result)
        }
    }

    suspend fun showResultToast(result: SeasonDownloadResult) {
        when (result) {
            SeasonDownloadResult.P2pDisabled -> {
                pendingEnqueue = {
                    (uiState as? PosterDownloadUiState.Ready)?.let { ready ->
                        enqueueReady(request, ready, ::showResultToast)
                    }
                }
                showP2pConsent = true
                return
            }
            SeasonDownloadResult.NoEpisodes -> NuvioToastController.show(noEpisodesText)
            SeasonDownloadResult.UnsupportedSource -> NuvioToastController.show(unsupportedText)
            is SeasonDownloadResult.Queued -> NuvioToastController.show(
                when {
                    result.queued == 0 && result.missingEpisodes.isEmpty() -> nothingNewText
                    result.missingEpisodes.isEmpty() ->
                        getString(Res.string.season_download_queued, result.queued)
                    else -> getString(
                        Res.string.season_download_queued_missing,
                        result.queued,
                        result.missingEpisodes.joinToString(", ") { "E$it" },
                    )
                },
            )
        }
        dismiss()
    }

    LaunchedEffect(request) {
        uiState = try {
            val meta = MetaDetailsRepository.peek(request.parentMetaType, request.parentMetaId)
                ?: MetaDetailsRepository.fetch(request.parentMetaType, request.parentMetaId)
            val videos = meta?.videos.orEmpty()
            val startVideo = if (request.isSeries) {
                WatchProgressRepository.ensureLoaded()
                val anchor = EpisodeDownloadPlanner.latestAnchor(
                    WatchProgressRepository.uiState.value.entries,
                    request.parentMetaId,
                )
                EpisodeDownloadPlanner.resolveStartVideo(videos, anchor)
            } else {
                MetaVideo(
                    id = request.parentMetaId,
                    title = request.title,
                    available = true,
                )
            }
            if (startVideo == null) {
                NuvioToastController.show(loadFailedText)
                PosterDownloadUiState.LoadFailed
            } else {
                val groups = EpisodeDownloadPlanner.fetchAddonStreams(
                    contentType = request.contentType,
                    videoId = startVideo.id,
                )
                val qualities = EpisodeDownloadPlanner.distinctQualities(groups.flatMap { it.streams })
                if (qualities.isEmpty()) {
                    PosterDownloadUiState.NoStreams
                } else {
                    PosterDownloadUiState.Ready(
                        startVideo = startVideo,
                        allVideos = videos,
                        groups = groups,
                        qualities = qualities,
                        selectedQuality = EpisodeDownloadPlanner.closestQuality(
                            qualities,
                            EpisodeDownloadPlanner.DEFAULT_QUALITY,
                        ) ?: qualities.first(),
                        selectedScope = PosterDownloadScope.REST_OF_SEASON,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NuvioToastController.show(loadFailedText)
            PosterDownloadUiState.LoadFailed
        }
    }

    if (showP2pConsent) {
        P2pConsentDialog(
            onEnableP2p = {
                showP2pConsent = false
                P2pSettingsRepository.setP2pEnabled(true)
                pendingEnqueue?.invoke()
                pendingEnqueue = null
            },
            onDismiss = {
                showP2pConsent = false
                pendingEnqueue = null
                dismiss()
            },
        )
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        when (val state = uiState) {
            PosterDownloadUiState.Loading -> DownloadOptionsLoading()
            PosterDownloadUiState.LoadFailed -> DownloadOptionsMessage(
                text = loadFailedText,
                onClose = dismiss,
            )
            PosterDownloadUiState.NoStreams -> DownloadOptionsMessage(
                text = noStreamsText,
                onClose = dismiss,
            )
            is PosterDownloadUiState.Ready -> DownloadOptionsContent(
                request = request,
                state = state,
                sheetState = sheetState,
                onStateChange = { uiState = it },
                onConfirm = {
                    enqueueReady(request, state, ::showResultToast)
                },
            )
        }
    }
}

@Composable
private fun DownloadOptionsLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DownloadOptionsMessage(
    text: String,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.nuvio.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        DownloadOptionChip(
            label = stringResource(Res.string.downloads_options_close),
            selected = true,
            onClick = onClose,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadOptionsContent(
    request: PosterDownloadRequest,
    state: PosterDownloadUiState.Ready,
    sheetState: SheetState,
    onStateChange: (PosterDownloadUiState.Ready) -> Unit,
    onConfirm: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = nuvioSafeBottomPadding(16.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_options_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = request.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        }
        NuvioBottomSheetDivider()
        if (request.isSeries) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DownloadOptionChip(
                        label = stringResource(Res.string.downloads_options_scope_rest_of_season),
                        selected = !state.customMode && state.selectedScope == PosterDownloadScope.REST_OF_SEASON,
                        onClick = {
                            onStateChange(
                                state.copy(
                                    selectedScope = PosterDownloadScope.REST_OF_SEASON,
                                    customMode = false,
                                ),
                            )
                        },
                    )
                    PosterDownloadScope.entries
                        .filter { it != PosterDownloadScope.REST_OF_SEASON }
                        .forEach { scope ->
                            DownloadOptionChip(
                                label = stringResource(
                                    Res.string.downloads_options_scope_next_episodes,
                                    scope.episodeCount ?: 0,
                                ),
                                selected = !state.customMode && state.selectedScope == scope,
                                onClick = {
                                    onStateChange(state.copy(selectedScope = scope, customMode = false))
                                },
                            )
                        }
                    DownloadOptionChip(
                        label = stringResource(downloads_options_scope_custom),
                        selected = state.customMode,
                        onClick = { onStateChange(state.copy(customMode = true)) },
                    )
                }
            }
            NuvioBottomSheetDivider()
            if (state.customMode) {
                CustomEpisodePicker(
                    request = request,
                    state = state,
                    onStateChange = onStateChange,
                )
                NuvioBottomSheetDivider()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_options_quality),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.qualities.forEach { quality ->
                    DownloadOptionChip(
                        label = quality,
                        selected = state.selectedQuality == quality,
                        onClick = { onStateChange(state.copy(selectedQuality = quality)) },
                    )
                }
            }
        }
        Button(
            onClick = {
                coroutineScope.launch {
                    dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onConfirm)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.nuvio.colors.accent,
                contentColor = MaterialTheme.nuvio.colors.onAccent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(Res.string.hero_download),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Manual episode picker for the "Custom" download scope: every season of the
 * show with per-episode checkboxes, Downloaded/Watched/"You're here" badges,
 * and a per-season whole-season toggle. The checked set lives in
 * [PosterDownloadUiState.Ready.customSelected]; the existing quality selector
 * and the pinned Download button keep working unchanged.
 */
@Composable
private fun CustomEpisodePicker(
    request: PosterDownloadRequest,
    state: PosterDownloadUiState.Ready,
    onStateChange: (PosterDownloadUiState.Ready) -> Unit,
) {
    // Same repository the Library Continue Watching section reads.
    val entries = remember { WatchProgressRepository.uiState.value.entries }
    val anchor = remember(request.parentMetaId) {
        EpisodeDownloadPlanner.latestAnchor(entries, request.parentMetaId)
    }
    val watchedSet = remember(entries, request.parentMetaId) {
        entries
            .filter { it.parentMetaId == request.parentMetaId && it.isCompleted }
            .mapNotNull { entry ->
                val s = entry.seasonNumber
                val e = entry.episodeNumber
                if (s != null && e != null) s to e else null
            }
            .toSet()
    }
    val isDownloaded: (Int, Int) -> Boolean = remember(request.parentMetaId) {
        { season, episode ->
            DownloadsRepository.hasActiveOrCompletedDownload(request.parentMetaId, season, episode)
        }
    }
    val seasons = remember(state.allVideos) {
        state.allVideos
            .filter { it.season != null && it.episode != null && it.available }
            .groupBy { it.season!! }
            .toSortedMap()
    }

    // Local functions are declared before their call sites (Kotlin requirement).
    fun toggleEpisode(season: Int, episode: Int) {
        val key = season to episode
        val next = if (state.customSelected.contains(key)) {
            state.customSelected - key
        } else {
            state.customSelected + key
        }
        onStateChange(state.copy(customSelected = next))
    }

    fun toggleSeason(season: Int, check: Boolean) {
        val keys = seasons[season].orEmpty().mapNotNull { video ->
            val episode = video.episode
            if (episode != null) season to episode else null
        }.toSet()
        val next = if (check) state.customSelected + keys else state.customSelected - keys
        onStateChange(state.copy(customSelected = next))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        seasons.forEach { (season, videos) ->
            CustomSeasonSection(
                season = season,
                videos = videos.sortedBy { it.episode },
                selected = state.customSelected,
                anchor = anchor,
                watchedSet = watchedSet,
                isDownloaded = isDownloaded,
                onToggleEpisode = ::toggleEpisode,
                onToggleSeason = ::toggleSeason,
            )
        }
    }
}

@Composable
private fun CustomSeasonSection(
    season: Int,
    videos: List<MetaVideo>,
    selected: Set<Pair<Int, Int>>,
    anchor: EpisodeAnchor?,
    watchedSet: Set<Pair<Int, Int>>,
    isDownloaded: (Int, Int) -> Boolean,
    onToggleEpisode: (Int, Int) -> Unit,
    onToggleSeason: (Int, Boolean) -> Unit,
) {
    val seasonLabel = if (season == 0) {
        stringResource(downloads_options_custom_specials)
    } else {
        stringResource(downloads_options_custom_season, season)
    }
    val downloadedCount = videos.count { isDownloaded(season, it.episode ?: 0) }
    val checkedCount = videos.count { video -> selected.contains(season to (video.episode ?: 0)) }
    val triState = when {
        videos.isNotEmpty() && checkedCount >= videos.size -> ToggleableState.On
        checkedCount == 0 -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleSeason(season, triState != ToggleableState.On) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = seasonLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = stringResource(
                        downloads_options_custom_downloaded_count,
                        downloadedCount,
                        videos.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
            }
            Text(
                text = stringResource(downloads_options_custom_whole_season),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
                modifier = Modifier.padding(end = 8.dp),
            )
            TriStateCheckbox(
                state = triState,
                onClick = { onToggleSeason(season, triState != ToggleableState.On) },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.nuvio.colors.accent,
                    checkmarkColor = MaterialTheme.nuvio.colors.onAccent,
                ),
            )
        }
        videos.forEach { video ->
            val episode = video.episode ?: return@forEach
            CustomEpisodeRow(
                video = video,
                checked = selected.contains(season to episode),
                downloaded = isDownloaded(season, episode),
                watched = watchedSet.contains(season to episode),
                isAnchor = anchor?.season == season && anchor?.episode == episode,
                onToggle = { onToggleEpisode(season, episode) },
            )
        }
    }
}

@Composable
private fun CustomEpisodeRow(
    video: MetaVideo,
    checked: Boolean,
    downloaded: Boolean,
    watched: Boolean,
    isAnchor: Boolean,
    onToggle: () -> Unit,
) {
    val episode = video.episode ?: return
    val title = video.title.takeIf { it.isNotBlank() } ?: "E$episode"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.nuvio.colors.accent,
                checkmarkColor = MaterialTheme.nuvio.colors.onAccent,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = "E$episode · $title",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (downloaded || watched || isAnchor) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (isAnchor) {
                        MiniBadge(
                            text = stringResource(downloads_options_custom_youre_here),
                            highlighted = true,
                        )
                    }
                    if (downloaded) {
                        MiniBadge(
                            text = stringResource(downloads_options_custom_downloaded),
                            highlighted = false,
                        )
                    }
                    if (watched) {
                        MiniBadge(
                            text = stringResource(downloads_options_custom_watched),
                            highlighted = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBadge(
    text: String,
    highlighted: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlighted) tokens.colors.onAccent else tokens.colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) tokens.colors.accent else Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DownloadOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = label,
        color = if (selected) tokens.colors.onAccent else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Shared quality-only picker used by the existing season-download flow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadQualitySheet(
    qualities: List<String>,
    selectedQuality: String,
    onQualitySelected: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = stringResource(Res.string.downloads_options_quality),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            NuvioBottomSheetDivider()
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                qualities.forEach { quality ->
                    DownloadOptionChip(
                        label = quality,
                        selected = selectedQuality == quality,
                        onClick = { onQualitySelected(quality) },
                    )
                }
            }
            Button(
                onClick = {
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onConfirm)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.nuvio.colors.accent,
                    contentColor = MaterialTheme.nuvio.colors.onAccent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.hero_download),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
