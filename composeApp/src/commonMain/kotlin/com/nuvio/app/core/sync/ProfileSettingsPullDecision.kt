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

/**
 * Whether the winning local state still has to be pushed after a pull. Only needed when
 * the pull was skipped because local state changed in flight: the local-change observer
 * drops events while a sync is in flight, so the change may never re-trigger a push.
 * If the changed local state already equals the remote blob, the server is up to date.
 */
internal fun shouldPushLocalAfterPull(
    decision: ProfileSettingsPullDecision,
    postFetchLocalSignature: String,
    remoteSignature: String,
): Boolean =
    decision == ProfileSettingsPullDecision.SkipLocalChangedDuringPull &&
        postFetchLocalSignature != remoteSignature
