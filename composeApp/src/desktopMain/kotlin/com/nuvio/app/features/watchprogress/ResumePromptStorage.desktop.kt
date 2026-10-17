package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object ResumePromptStorage {
    private const val wasInPlayerKey = "resume_prompt_was_in_player"
    private const val lastPlayerVideoIdKey = "resume_prompt_last_player_video_id"

    private val prefs = DesktopPreferences("resume_prompt")

    actual fun loadWasInPlayer(): Boolean =
        prefs.getBoolean(ProfileScopedKey.of(wasInPlayerKey)) ?: false

    actual fun saveWasInPlayer(value: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(wasInPlayerKey), value)
    }

    actual fun loadLastPlayerVideoId(): String? =
        prefs.getString(ProfileScopedKey.of(lastPlayerVideoIdKey))

    actual fun saveLastPlayerVideoId(videoId: String?) {
        val key = ProfileScopedKey.of(lastPlayerVideoIdKey)
        if (videoId != null) {
            prefs.putString(key, videoId)
        } else {
            prefs.remove(key)
        }
    }
}
