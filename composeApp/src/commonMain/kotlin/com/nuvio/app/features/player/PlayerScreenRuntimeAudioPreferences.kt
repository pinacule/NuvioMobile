package com.nuvio.app.features.player

/**
 * English indicators in audio track labels, for tracks whose container carries no
 * language tag (common in scene releases): e.g. "Track 2 (AAC) - English Dub".
 * Only used as a fallback when no language-tagged English track was selected.
 */
private val englishTrackLabelKeywords = Regex(
    "\\benglish\\b|\\beng\\b|\\bdubs?\\b|\\bdubbed\\b",
    RegexOption.IGNORE_CASE,
)

/** True when the track is English: by language tag, or (for untagged tracks) by label keywords. */
private fun isEnglishAudioTrack(track: AudioTrack): Boolean {
    val language = normalizeLanguageCode(track.language)
    // Compare the primary subtag so regional tags ("en-US", "en-GB") count as English.
    if (language?.substringBefore('-') == "en") return true
    // Effectively-untagged tracks ("und" etc.): fall back to label keywords.
    // A recognized non-English tag wins over the label (don't steal real tracks).
    if (language != null && language !in untaggedLanguageCodes) return false
    return englishTrackLabelKeywords.containsMatchIn(track.label)
}

// ISO 639 codes meaning "no usable language info" — treated as untagged.
private val untaggedLanguageCodes = setOf("und", "unknown", "mis", "mul", "zxx")

internal val PlayerScreenRuntime.contentLanguage: String?
    get() {
        val metadata = listOfNotNull(metaUiState.meta, playerMeta).filter {
            it.id == parentMetaId && it.type == parentMetaType
        }
        return metadata.firstNotNullOfOrNull { normalizeLanguageCode(it.language) }
            ?: normalizeLanguageCode(args.contentLanguage)
            ?: metadata.firstNotNullOfOrNull { countryToLanguageCode(it.country) }
    }

internal val PlayerScreenRuntime.preferredAudioLanguageTargets: List<String>
    get() = resolvePreferredAudioLanguageTargets(
        preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        contentOriginalLanguage = contentLanguage,
    )

internal data class AppliedAudioPreferences(
    val controller: PlayerEngineController,
    val languages: List<String>,
    val tracks: List<AudioTrack>,
)

internal fun PlayerScreenRuntime.applyPreferredAudioTrack(targets: List<String>) {
    if (isUserExplicitAudioSelection) return
    val controller = playerController ?: return
    val preferences = AppliedAudioPreferences(
        controller = controller,
        languages = targets,
        tracks = audioTracks.map { it.copy(isSelected = false) },
    )
    if (appliedAudioPreferences != preferences) {
        controller.applyAudioLanguagePreferences(targets)
        appliedAudioPreferences = preferences
        preferredAudioSelectionApplied = false
    } else {
        preferredAudioSelectionApplied = true
    }
    // ExoPlayer's language preference only works when the MKV's English track
    // carries a language tag; untagged English tracks are missed, so select
    // them by label as an automatic fallback (no manual swap needed).
    applyEnglishAudioLabelFallback(targets)
}

/**
 * Automatic English-audio enforcement for untagged tracks: when English is the
 * user's TOP audio target and the selected track isn't English, select the
 * first English-indicated track explicitly. Only fires when English outranks
 * every other target — when the preference system deliberately picked a
 * higher-priority language (e.g. "original audio" mode), it is left alone.
 * Idempotent — a no-op when the English track is already selected. Never
 * overrides a manual user selection ([isUserExplicitAudioSelection]).
 */
private fun PlayerScreenRuntime.applyEnglishAudioLabelFallback(targets: List<String>) {
    if (isUserExplicitAudioSelection) return
    if (targets.firstOrNull()?.substringBefore('-')?.equals("en", ignoreCase = true) != true) return
    val tracks = audioTracks
    if (tracks.isEmpty()) return
    val selected = tracks.firstOrNull { it.isSelected }
    if (selected != null && isEnglishAudioTrack(selected)) return
    val englishTrack = tracks.firstOrNull { isEnglishAudioTrack(it) } ?: return
    if (englishTrack.index == selected?.index) return
    playerController?.selectAudioTrack(englishTrack.index)
}

internal fun PlayerScreenRuntime.restorePersistedAudioPreference(preference: PersistedPlayerTrackPreference) {
    if (audioTracks.isEmpty()) return
    val restoredIndex = findPersistedAudioTrackIndex(audioTracks, preference)
    if (restoredIndex < 0) return
    playerController?.selectAudioTrack(restoredIndex)
    selectedAudioIndex = restoredIndex
    isUserExplicitAudioSelection = true
    preferredAudioSelectionApplied = true
}

internal fun PlayerScreenRuntime.refreshAudioTracksIfChanged() {
    if (playbackSnapshot.isLoading) return
    val controller = playerController ?: return
    if (!preferredAudioSelectionApplied || controller.getAudioTracks() != audioTracks) {
        refreshTracks()
    }
}
