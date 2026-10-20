package com.nuvio.app.core.sync

internal enum class ProfileSettingsPullDecision {
    /** Local state did not change while the pull was in flight and differs from remote — apply the remote blob. */
    ApplyRemote,

    /** Remote already matches the current local state — nothing to do. */
    SkipRemoteMatchesLocal,

    /**
     * The user changed settings while the pull was in flight. The local change wins:
     * the remote blob must not overwrite it.
     */
    SkipLocalChangedDuringPull,
}

/**
 * Decides what a profile settings pull should do once the remote blob arrives.
 *
 * [preFetchLocalSignature] is the local signature captured before the pull RPC started,
 * [postFetchLocalSignature] is the local signature re-read after the RPC returned, and
 * [remoteSignature] is the signature of the fetched remote blob.
 */
internal fun decideProfileSettingsPull(
    preFetchLocalSignature: String,
    postFetchLocalSignature: String,
    remoteSignature: String,
): ProfileSettingsPullDecision = when {
    postFetchLocalSignature != preFetchLocalSignature ->
        ProfileSettingsPullDecision.SkipLocalChangedDuringPull
    remoteSignature == postFetchLocalSignature ->
        ProfileSettingsPullDecision.SkipRemoteMatchesLocal
    else ->
        ProfileSettingsPullDecision.ApplyRemote
}

/** Every way a profile settings pull can end. */
internal enum class ProfileSettingsPullExit {
    /** The remote blob was decoded and applied locally. */
    AppliedRemote,

    /** The decision found the remote blob already matches local state. */
    RemoteMatchedLocal,

    /** The decision found local state changed while the pull was in flight; local wins. */
    LocalChangedDuringPull,

    /** The server returned no settings blob for this profile. */
    NoRemoteBlob,

    /** The remote blob could not be decoded. */
    UndecodableRemote,

    /** The pull failed (RPC/transport exception or any other error). */
    Failed,

    /** The pulled profile stopped being the active profile before the pull finished. */
    ProfileSwitched,
}

/** What the pull-exit settle must do about local state that may have changed in flight. */
internal enum class ProfileSettingsPullExitAction {
    /** Nothing to push and nothing to record. */
    None,

    /** Push the pulled profile's current local state now (bound to that profile). */
    PushLocalNow,

    /**
     * The pulled profile's local state cannot be read right now (settings are scoped
     * to the active profile), so record a pending push instead; it is retried before
     * that profile's next pull, before any whole-blob apply can overwrite the change.
     */
    MarkPendingPush,
}

/**
 * Unified pull-exit discipline: decides, for EVERY pull exit, whether a local change
 * made while the pull was in flight still has to reach the server.
 *
 * The local-change observer drops events while a sync is in flight, so a change that
 * is not pushed here (or recorded as pending) may never re-trigger a push and would
 * be silently overwritten by the next successful pull's whole-blob apply.
 *
 * [preFetchLocalSignature] is the pulled profile's local signature captured before
 * the pull RPC, [exitLocalSignature] is its local signature re-read at the exit
 * (null when the pulled profile is no longer active and its state cannot be read),
 * [remoteSignature] is the fetched remote blob's signature when one was decoded, and
 * [appliedRemoteSignature] is the signature of the blob this pull applied, if any.
 */
internal fun decideProfileSettingsPullExitAction(
    exit: ProfileSettingsPullExit,
    pulledProfileStillActive: Boolean,
    preFetchLocalSignature: String?,
    exitLocalSignature: String?,
    remoteSignature: String?,
    appliedRemoteSignature: String?,
): ProfileSettingsPullExitAction {
    if (!pulledProfileStillActive) {
        // The pulled profile's local state cannot be read or compared, so no
        // signature check is possible. Record a pending push; pushing that
        // profile's then-current local state before its next pull is the only
        // way to guarantee an in-flight change is not stranded.
        return ProfileSettingsPullExitAction.MarkPendingPush
    }
    val localChangedDuringPull = preFetchLocalSignature != null &&
        exitLocalSignature != null &&
        exitLocalSignature != preFetchLocalSignature
    return when (exit) {
        ProfileSettingsPullExit.AppliedRemote ->
            // A change that landed during applyRemoteBlob leaves the post-apply
            // signature diverged from the applied blob's signature. It survives
            // locally (whatever the apply did not overwrite) and must be pushed
            // instead of being absorbed by the post-apply push-skip signature.
            if (appliedRemoteSignature != null && exitLocalSignature != appliedRemoteSignature) {
                ProfileSettingsPullExitAction.PushLocalNow
            } else {
                ProfileSettingsPullExitAction.None
            }

        ProfileSettingsPullExit.LocalChangedDuringPull ->
            // Local won over remote. It only needs a push if the server does not
            // already hold exactly this state.
            if (remoteSignature != null && exitLocalSignature != remoteSignature) {
                ProfileSettingsPullExitAction.PushLocalNow
            } else {
                ProfileSettingsPullExitAction.None
            }

        ProfileSettingsPullExit.RemoteMatchedLocal,
        ProfileSettingsPullExit.NoRemoteBlob,
        ProfileSettingsPullExit.UndecodableRemote,
        ProfileSettingsPullExit.Failed ->
            // Nothing was applied, so the pre-fetch comparison is the whole story:
            // if local state moved while the pull was in flight, the change is
            // pushed now (or recorded pending by the caller if that push fails).
            if (localChangedDuringPull) {
                ProfileSettingsPullExitAction.PushLocalNow
            } else {
                ProfileSettingsPullExitAction.None
            }

        ProfileSettingsPullExit.ProfileSwitched ->
            // The profile is active again by the time the exit settles (it was
            // switched away and back). Prefer the signature comparison; if it
            // shows no change, still record a pending push, because signatures
            // captured across a switch are not fully trustworthy.
            if (localChangedDuringPull) {
                ProfileSettingsPullExitAction.PushLocalNow
            } else {
                ProfileSettingsPullExitAction.MarkPendingPush
            }
    }
}
