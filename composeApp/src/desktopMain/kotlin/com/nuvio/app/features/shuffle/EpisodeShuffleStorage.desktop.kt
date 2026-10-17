package com.nuvio.app.features.shuffle

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object EpisodeShuffleStorage {
    private val prefs = DesktopPreferences("episode_shuffle")

    actual fun load(profileId: Int): String? =
        prefs.getString(payloadKey(profileId))

    actual fun save(profileId: Int, payload: String) {
        prefs.putString(payloadKey(profileId), payload)
    }

    private fun payloadKey(profileId: Int): String = "episode_shuffle_$profileId"
}
