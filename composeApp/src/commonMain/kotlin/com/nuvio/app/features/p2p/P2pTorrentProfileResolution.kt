package com.nuvio.app.features.p2p

/**
 * The torrent profile applied when the user has never recorded an explicit
 * choice in P2P settings. Since batch 20 this is [P2pTorrentProfile.FAST]:
 * the user asked for the fastest profile for speed, and installs that never
 * picked a profile were silently sitting on the old BALANCED default.
 */
internal val DefaultP2pTorrentProfile: P2pTorrentProfile = P2pTorrentProfile.FAST

internal data class TorrentProfileResolution(
    /** The effective profile to use. */
    val profile: P2pTorrentProfile,
    /**
     * Non-null when the caller must persist this profile together with the
     * explicit-choice marker. This is the one-time migration write: once it
     * has happened the marker is set, and resolution never asks to write
     * again, so the migration cannot re-apply over a later user pick.
     */
    val profileToPersist: P2pTorrentProfile?,
)

/**
 * Resolves the effective torrent profile from the stored value and the
 * explicit-choice marker.
 *
 * A recorded explicit choice (the marker is set, either because the user
 * picked a profile in settings or because the migration below already ran)
 * is always honored. Without a recorded choice — a fresh install, or an
 * install from before the marker existed that only ever ran the old
 * BALANCED default — the profile resolves to [DefaultP2pTorrentProfile] and
 * [TorrentProfileResolution.profileToPersist] tells the caller to record
 * that migration exactly once. After the migration, normal settings
 * behavior applies: whatever the user picks is stored with the marker and
 * is never overridden.
 */
internal fun resolveTorrentProfile(
    storedProfileName: String?,
    explicitlySet: Boolean,
): TorrentProfileResolution {
    if (explicitlySet) {
        val stored = storedProfileName?.let { name ->
            P2pTorrentProfile.entries.firstOrNull { it.name == name }
        }
        return TorrentProfileResolution(
            profile = stored ?: DefaultP2pTorrentProfile,
            profileToPersist = null,
        )
    }
    return TorrentProfileResolution(
        profile = DefaultP2pTorrentProfile,
        profileToPersist = DefaultP2pTorrentProfile,
    )
}
