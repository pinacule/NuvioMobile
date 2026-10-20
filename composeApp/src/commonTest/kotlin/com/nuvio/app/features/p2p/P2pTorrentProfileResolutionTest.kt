package com.nuvio.app.features.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class P2pTorrentProfileResolutionTest {

    @Test
    fun freshInstallResolvesToFastAndRequestsMigrationWrite() {
        val resolution = resolveTorrentProfile(storedProfileName = null, explicitlySet = false)

        assertEquals(P2pTorrentProfile.FAST, resolution.profile)
        assertEquals(P2pTorrentProfile.FAST, resolution.profileToPersist)
    }

    @Test
    fun storedExplicitBalancedStaysBalanced() {
        val resolution = resolveTorrentProfile(storedProfileName = "BALANCED", explicitlySet = true)

        assertEquals(P2pTorrentProfile.BALANCED, resolution.profile)
        assertNull(resolution.profileToPersist)
    }

    @Test
    fun storedExplicitSoftStaysSoft() {
        val resolution = resolveTorrentProfile(storedProfileName = "SOFT", explicitlySet = true)

        assertEquals(P2pTorrentProfile.SOFT, resolution.profile)
        assertNull(resolution.profileToPersist)
    }

    @Test
    fun storedValueWithoutExplicitMarkerMigratesToFast() {
        // Pre-flag installs could hold a stored value (e.g. the old BALANCED
        // default picked through, or an old value from before the marker
        // existed) without it being a recorded explicit choice.
        val resolution = resolveTorrentProfile(storedProfileName = "BALANCED", explicitlySet = false)

        assertEquals(P2pTorrentProfile.FAST, resolution.profile)
        assertEquals(P2pTorrentProfile.FAST, resolution.profileToPersist)
    }

    @Test
    fun migrationAppliesExactlyOnce() {
        // Simulate the migration write: storage now holds FAST + marker set.
        val afterMigration = resolveTorrentProfile(storedProfileName = "FAST", explicitlySet = true)

        assertEquals(P2pTorrentProfile.FAST, afterMigration.profile)
        assertNull(afterMigration.profileToPersist)
    }

    @Test
    fun pickAfterMigrationIsNeverOverridden() {
        // The user changes their mind after the migration and picks Balanced:
        // the pick is stored with the marker, so resolution keeps it.
        val resolution = resolveTorrentProfile(storedProfileName = "BALANCED", explicitlySet = true)

        assertEquals(P2pTorrentProfile.BALANCED, resolution.profile)
        assertNull(resolution.profileToPersist)
    }

    @Test
    fun explicitMarkerWithMissingStoredValueFallsBackToFast() {
        val resolution = resolveTorrentProfile(storedProfileName = null, explicitlySet = true)

        assertEquals(P2pTorrentProfile.FAST, resolution.profile)
        assertNull(resolution.profileToPersist)
    }

    @Test
    fun explicitMarkerWithUnknownStoredValueFallsBackToFast() {
        val resolution = resolveTorrentProfile(storedProfileName = "ULTRA", explicitlySet = true)

        assertEquals(P2pTorrentProfile.FAST, resolution.profile)
        assertNull(resolution.profileToPersist)
    }
}
