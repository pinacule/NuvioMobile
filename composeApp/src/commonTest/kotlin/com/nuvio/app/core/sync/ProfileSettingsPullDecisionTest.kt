package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSettingsPullDecisionTest {
    @Test
    fun `local change during pull wins over remote and stays eligible for push`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "local-before",
            postFetchLocalSignature = "local-after-user-change",
            remoteSignature = "remote",
        )

        assertEquals(ProfileSettingsPullDecision.SkipLocalChangedDuringPull, decision)
        assertTrue(
            shouldPushLocalAfterPull(
                decision = decision,
                postFetchLocalSignature = "local-after-user-change",
                remoteSignature = "remote",
            ),
        )
    }

    @Test
    fun `local change during pull that already matches remote needs no push`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "local-before",
            postFetchLocalSignature = "shared-state",
            remoteSignature = "shared-state",
        )

        assertEquals(ProfileSettingsPullDecision.SkipLocalChangedDuringPull, decision)
        assertFalse(
            shouldPushLocalAfterPull(
                decision = decision,
                postFetchLocalSignature = "shared-state",
                remoteSignature = "shared-state",
            ),
        )
    }

    @Test
    fun `remote is applied when local state did not change during pull`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "local",
            postFetchLocalSignature = "local",
            remoteSignature = "remote",
        )

        assertEquals(ProfileSettingsPullDecision.ApplyRemote, decision)
        assertFalse(
            shouldPushLocalAfterPull(
                decision = decision,
                postFetchLocalSignature = "local",
                remoteSignature = "remote",
            ),
        )
    }

    @Test
    fun `identical signatures skip the apply`() {
        val decision = decideProfileSettingsPull(
            preFetchLocalSignature = "same",
            postFetchLocalSignature = "same",
            remoteSignature = "same",
        )

        assertEquals(ProfileSettingsPullDecision.SkipRemoteMatchesLocal, decision)
        assertFalse(
            shouldPushLocalAfterPull(
                decision = decision,
                postFetchLocalSignature = "same",
                remoteSignature = "same",
            ),
        )
    }
}
