package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.ServerPlaybackSession
import com.nuvio.app.features.servers.label
import com.nuvio.app.features.servers.readableTranscodeReason
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_audio_switch_failed
import nuvio.composeapp.generated.resources.servers_subtitle_switch_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal val PlayerScreenRuntime.hasBurnedInServerSubtitle: Boolean
    get() = ServerPlayback.burnInSubtitles(activeSourceUrl).any { it.selected }

internal fun PlayerScreenRuntime.refreshServerTracks() {
    serverAudioTracks = ServerPlayback.audioTracks(activeSourceUrl).map { track ->
        AudioTrack(
            index = track.index,
            id = track.index.toString(),
            label = track.label,
            language = track.language,
            isSelected = track.selected,
        )
    }
    serverSubtitleTracks = ServerPlayback.burnInSubtitles(activeSourceUrl).map { track ->
        SubtitleTrack(
            index = track.index,
            id = track.index.toString(),
            label = track.label,
            language = track.language,
            isSelected = track.selected,
        )
    }
    updateServerSubtitleSelectionFlags()
}

/**
 * Decides what the server's currently selected subtitle means (audit
 * playback finding 9). Previously ANY selected server subtitle marked
 * the selection as an explicit user choice — but a media server applies
 * its own default subtitle the moment a session starts, before the user
 * touches anything, and that false "explicit" flag then blocked the
 * per-series restore and let later auto-picks be persisted as if the
 * user had chosen them.
 *
 * The first selection seen for a source identity is recorded as the
 * server DEFAULT baseline and only counts as "a selection is in effect"
 * ([preferredSubtitleSelectionApplied] — the don't-stack-an-auto-pick
 * guard). [isUserExplicitSubtitleSelection] is set only when the change
 * was driven by the subtitle modal (select/clear set
 * [serverSubtitleUserDrivenChange] before restarting the stream) — a
 * selection change arriving any other way is the server renegotiating
 * its own default and just moves the baseline.
 */
private fun PlayerScreenRuntime.updateServerSubtitleSelectionFlags() {
    val selectedIndex = serverSubtitleTracks.firstOrNull { it.isSelected }?.index
    val identityKey = "$activeSourceIdentityKey:$activeVideoId"
    when {
        serverDefaultSubtitleKey != identityKey -> {
            serverDefaultSubtitleKey = identityKey
            serverDefaultSubtitleIndex = selectedIndex
            serverSubtitleUserDrivenChange = false
            if (selectedIndex != null) preferredSubtitleSelectionApplied = true
        }
        serverSubtitleUserDrivenChange -> {
            serverSubtitleUserDrivenChange = false
            serverDefaultSubtitleIndex = selectedIndex
            if (selectedIndex != null) {
                isUserExplicitSubtitleSelection = true
                preferredSubtitleSelectionApplied = true
            }
        }
        selectedIndex != null && selectedIndex != serverDefaultSubtitleIndex -> {
            serverDefaultSubtitleIndex = selectedIndex
            preferredSubtitleSelectionApplied = true
        }
        selectedIndex != null -> {
            // The server's own default, still in effect: a selection is
            // applied, but it is NOT a user choice.
            preferredSubtitleSelectionApplied = true
        }
    }
}

@Composable
internal fun PlayerScreenRuntime.serverPlaybackSummary(): String? {
    val session = ServerPlayback.session(activeSourceUrl) ?: return null
    val method = stringResource(session.playMethod.label())
    val reasons = session.transcodeReasons.joinToString(", ", transform = ::readableTranscodeReason)
    return if (reasons.isEmpty()) method else "$method · $reasons"
}

internal fun PlayerScreenRuntime.applyPreferredServerAudioTrack() {
    if (serverAudioTracks.isEmpty() || isUserExplicitAudioSelection) return
    val key = "$activeSourceIdentityKey:$activeVideoId"
    if (serverAudioPreferenceKey == key) return
    serverAudioPreferenceKey = key
    val index = preferredServerAudioIndex(
        tracks = serverAudioTracks,
        preference = PlayerTrackPreferenceStorage.load(parentMetaId),
        targets = preferredAudioLanguageTargets,
    ) ?: return
    switchServerAudioTrack(index)
}

internal fun preferredServerAudioIndex(
    tracks: List<AudioTrack>,
    preference: PersistedPlayerTrackPreference?,
    targets: List<String>,
): Int? =
    preference?.let { findPersistedAudioTrackIndex(tracks, it) }?.takeIf { it >= 0 }
        ?: targets.firstNotNullOfOrNull { target -> tracks.firstOrNull { audioTrackMatchesLanguage(it, target) }?.index }

internal fun PlayerScreenRuntime.selectServerAudioTrack(index: Int) {
    persistAudioPreference(serverAudioTracks.firstOrNull { it.index == index })
    switchServerAudioTrack(index)
}

internal fun PlayerScreenRuntime.selectServerSubtitleTrack(index: Int) {
    if (serverSubtitleTracks.any { it.isSelected && it.index == index }) return
    serverSubtitleUserDrivenChange = true
    isUserExplicitSubtitleSelection = true
    preferredSubtitleSelectionApplied = true
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    if (useCustomSubtitles) {
        playerController?.clearExternalSubtitleAndSelect(-1)
    } else {
        playerController?.selectSubtitleTrack(-1)
    }
    useCustomSubtitles = false
    restartServerStream(Res.string.servers_subtitle_switch_failed) { url -> ServerPlayback.switchSubtitle(url, index) }
}

internal fun PlayerScreenRuntime.clearServerSubtitleTrack() {
    // Turning subtitles off IS a user choice: record it like a selection
    // (previously this set neither flag, so the choice wasn't honoured as
    // explicit by the restore/auto-sync guards).
    serverSubtitleUserDrivenChange = true
    isUserExplicitSubtitleSelection = true
    preferredSubtitleSelectionApplied = true
    restartServerStream(Res.string.servers_subtitle_switch_failed) { url -> ServerPlayback.switchSubtitle(url, null) }
}

private fun PlayerScreenRuntime.switchServerAudioTrack(index: Int) {
    if (serverAudioTracks.any { it.isSelected && it.index == index }) return
    restartServerStream(Res.string.servers_audio_switch_failed) { url -> ServerPlayback.switchAudio(url, index) }
}

private fun PlayerScreenRuntime.restartServerStream(
    failureMessage: StringResource,
    restart: suspend (String) -> ServerPlaybackSession?,
) {
    if (serverTrackSwitchJob?.isActive == true) return
    val url = activeSourceUrl
    val positionMs = if (initialSeekApplied) playbackSnapshot.positionMs.coerceAtLeast(0L) else activeInitialPositionMs
    serverTrackSwitchJob = scope.launch {
        val playback = restart(url)
        if (playback == null) {
            NuvioToastController.show(getString(failureMessage))
            return@launch
        }
        if (activeSourceUrl != url) {
            ServerPlayback.stop(playback.url)
            return@launch
        }
        externalSubtitles = playback.subtitles
        activeSourceUrl = playback.url
        activeSourceHeaders = playback.headers
        activeInitialPositionMs = positionMs
        activeInitialProgressFraction = null
        trackPreferenceRestoreApplied = false
    }
}
