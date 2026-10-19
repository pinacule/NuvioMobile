package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_options_close
import nuvio.composeapp.generated.resources.downloads_options_load_failed
import nuvio.composeapp.generated.resources.downloads_options_no_streams
import nuvio.composeapp.generated.resources.downloads_options_quality
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

    // Local functions are declared before their call sites (Kotlin requirement).
    fun enqueueReady(
        req: PosterDownloadRequest,
        ready: PosterDownloadUiState.Ready,
        onResult: (SeasonDownloadResult) -> Unit,
    ) {
        val targets = when {
            !req.isSeries -> listOf(ready.startVideo)
            ready.selectedScope == PosterDownloadScope.REST_OF_SEASON ->
                EpisodeDownloadPlanner.restOfSeasonVideos(ready.allVideos, ready.startVideo)
            else -> EpisodeDownloadPlanner.nextNVideos(
                ready.allVideos,
                ready.startVideo,
                ready.selectedScope.episodeCount ?: 3,
            )
        }
        if (targets.isEmpty()) {
            NuvioToastController.show(noEpisodesText)
            return
        }
        val template = EpisodeDownloadPlanner.pickBestStreamAtQuality(
            ready.groups.flatMap { it.streams },
            ready.selectedQuality,
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

    fun showResultToast(result: SeasonDownloadResult) {
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
                        selected = state.selectedScope == PosterDownloadScope.REST_OF_SEASON,
                        onClick = { onStateChange(state.copy(selectedScope = PosterDownloadScope.REST_OF_SEASON)) },
                    )
                    PosterDownloadScope.entries
                        .filter { it != PosterDownloadScope.REST_OF_SEASON }
                        .forEach { scope ->
                            DownloadOptionChip(
                                label = stringResource(
                                    Res.string.downloads_options_scope_next_episodes,
                                    scope.episodeCount ?: 0,
                                ),
                                selected = state.selectedScope == scope,
                                onClick = { onStateChange(state.copy(selectedScope = scope)) },
                            )
                        }
                }
            }
            NuvioBottomSheetDivider()
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
