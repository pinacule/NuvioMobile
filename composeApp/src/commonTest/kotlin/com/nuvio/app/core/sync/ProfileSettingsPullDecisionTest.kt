package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSettingsPullDecisionTest {
    @Test
    fun `local change during pull wins over remote`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "local-before",
            postFetchLocalSignature = "local-after-user-change",
            remoteSignature = "remote",
        )

        assertEquals(ProfileSettingsPullDecision.SkipLocalChangedDuringPull, decision)
    }

    @Test
    fun `local change during pull that lands on the remote state still wins the decision`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "local-before",
            postFetchLocalSignature = "shared-state",
            remoteSignature = "shared-state",
        )

        assertEquals(ProfileSettingsPullDecision.SkipLocalChangedDuringPull, decision)
    }

    @Test
    fun `remote is applied when local state did not change during pull`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "local",
            postFetchLocalSignature = "local",
            remoteSignature = "remote",
        )

        assertEquals(ProfileSettingsPullDecision.ApplyRemote, decision)
    }

    @Test
    fun `identical signatures skip the apply`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "same",
            postFetchLocalSignature = "same",
            remoteSignature = "same",
        )

        assertEquals(ProfileSettingsPullDecision.SkipRemoteMatchesLocal, decision)
    }

    // --- decideProfileSettingsPullExitAction: failure exits (R-3) ---

    @Test
    fun `null remote exit pushes a change made during the pull`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.NoRemoteBlob,
                preFetch = "before",
                exitLocal = "after-user-change",
                remote = null,
            ),
        )
    }

    @Test
    fun `null remote exit does nothing when local state did not change`() {
        assertEquals(
            ProfileSettingsPullExitAction.None,
            exitAction(
                exit = ProfileSettingsPullExit.NoRemoteBlob,
                preFetch = "same",
                exitLocal = "same",
                remote = null,
            ),
        )
    }

    @Test
    fun `undecodable remote exit pushes a change made during the pull`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.UndecodableRemote,
                preFetch = "before",
                exitLocal = "after-user-change",
                remote = null,
            ),
        )
    }

    @Test
    fun `failed pull exit pushes a change made during the pull`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.Failed,
                preFetch = "before",
                exitLocal = "after-user-change",
                remote = null,
            ),
        )
    }

    @Test
    fun `failed pull exit does nothing when local state did not change`() {
        assertEquals(
            ProfileSettingsPullExitAction.None,
            exitAction(
                exit = ProfileSettingsPullExit.Failed,
                preFetch = "same",
                exitLocal = "same",
                remote = null,
            ),
        )
    }

    @Test
    fun `failed pull exit without a pre-fetch snapshot does nothing`() {
        assertEquals(
            ProfileSettingsPullExitAction.None,
            exitAction(
                exit = ProfileSettingsPullExit.Failed,
                preFetch = null,
                exitLocal = "anything",
                remote = null,
            ),
        )
    }

    // --- decideProfileSettingsPullExitAction: decision exits ---

    @Test
    fun `local-changed decision exit pushes when the server does not hold the change`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.LocalChangedDuringPull,
                preFetch = "local-before",
                exitLocal = "local-after-user-change",
                remote = "remote",
            ),
        )
    }

    @Test
    fun `local-changed decision exit skips the push when local already matches remote`() {
        assertEquals(
            ProfileSettingsPullExitAction.None,
            exitAction(
                exit = ProfileSettingsPullExit.LocalChangedDuringPull,
                preFetch = "local-before",
                exitLocal = "shared-state",
                remote = "shared-state",
            ),
        )
    }

    @Test
    fun `remote-matched exit does nothing when local state did not change`() {
        assertEquals(
            ProfileSettingsPullExitAction.None,
            exitAction(
                exit = ProfileSettingsPullExit.RemoteMatchedLocal,
                preFetch = "same",
                exitLocal = "same",
                remote = "same",
            ),
        )
    }

    @Test
    fun `remote-matched exit pushes a change that landed after the decision`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.RemoteMatchedLocal,
                preFetch = "before",
                exitLocal = "after-late-change",
                remote = "before",
            ),
        )
    }

    // --- decideProfileSettingsPullExitAction: apply exit (R-12) ---

    @Test
    fun `applied exit does nothing when the applied state is exactly what landed`() {
        assertEquals(
            ProfileSettingsPullExitAction.None,
            exitAction(
                exit = ProfileSettingsPullExit.AppliedRemote,
                preFetch = "local",
                exitLocal = "remote",
                remote = "remote",
                applied = "remote",
            ),
        )
    }

    @Test
    fun `applied exit pushes a change that landed during the apply`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.AppliedRemote,
                preFetch = "local",
                exitLocal = "remote-plus-user-change",
                remote = "remote",
                applied = "remote",
            ),
        )
    }

    // --- decideProfileSettingsPullExitAction: profile no longer active (R-10) ---

    @Test
    fun `any exit marks a pending push when the pulled profile is no longer active`() {
        for (exit in ProfileSettingsPullExit.entries) {
            assertEquals(
                ProfileSettingsPullExitAction.MarkPendingPush,
                decideProfileSettingsPullExitAction(
                    exit = exit,
                    pulledProfileStillActive = false,
                    preFetchLocalSignature = "before",
                    exitLocalSignature = null,
                    remoteSignature = null,
                    appliedRemoteSignature = null,
                ),
                "exit=$exit",
            )
        }
    }

    @Test
    fun `profile switched exit with an unchanged active profile still marks pending`() {
        assertEquals(
            ProfileSettingsPullExitAction.MarkPendingPush,
            exitAction(
                exit = ProfileSettingsPullExit.ProfileSwitched,
                preFetch = "same",
                exitLocal = "same",
                remote = null,
            ),
        )
    }

    @Test
    fun `profile switched exit with a changed active profile pushes`() {
        assertEquals(
            ProfileSettingsPullExitAction.PushLocalNow,
            exitAction(
                exit = ProfileSettingsPullExit.ProfileSwitched,
                preFetch = "before",
                exitLocal = "after-user-change",
                remote = null,
            ),
        )
    }

    private fun exitAction(
        exit: ProfileSettingsPullExit,
        preFetch: String?,
        exitLocal: String?,
        remote: String?,
        applied: String? = null,
    ): ProfileSettingsPullExitAction = decideProfileSettingsPullExitAction(
        exit = exit,
        pulledProfileStillActive = true,
        preFetchLocalSignature = preFetch,
        exitLocalSignature = exitLocal,
        remoteSignature = remote,
        appliedRemoteSignature = applied,
    )
}
