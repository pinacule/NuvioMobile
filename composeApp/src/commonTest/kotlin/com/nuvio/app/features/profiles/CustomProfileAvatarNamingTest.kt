package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomProfileAvatarNamingTest {

    @Test
    fun legacyFileNameParsesAsRevisionZero() {
        assertEquals(
            CustomAvatarFileRef(profileIndex = 3, revision = 0L),
            parseCustomAvatarFileName("profile_3.jpg"),
        )
        assertEquals("profile_3.jpg", customAvatarFileName(profileIndex = 3, revision = 0L))
    }

    @Test
    fun revisionedFileNameRoundTrips() {
        val name = customAvatarFileName(profileIndex = 2, revision = 7L)
        assertEquals("profile_2_7.jpg", name)
        assertEquals(
            CustomAvatarFileRef(profileIndex = 2, revision = 7L),
            parseCustomAvatarFileName(name),
        )
    }

    @Test
    fun nonAvatarFileNamesDoNotParse() {
        assertNull(parseCustomAvatarFileName(".profile_2_7.jpg.tmp"))
        assertNull(parseCustomAvatarFileName("profile_x.jpg"))
        assertNull(parseCustomAvatarFileName("profile_2.png"))
        assertNull(parseCustomAvatarFileName("profile_.jpg"))
        assertNull(parseCustomAvatarFileName("profile_2_7_extra.jpg"))
        assertNull(parseCustomAvatarFileName("avatar_2.jpg"))
    }

    @Test
    fun consecutiveSavesProduceDifferentUris() {
        // Simulate the storage scan -> next revision -> file name flow that a
        // save performs: each save must expose a different path than the last,
        // otherwise state flows stay conflated and image caches serve stale.
        val firstRevision = nextCustomAvatarRevision(existingRevisions = emptyList())
        val firstName = customAvatarFileName(profileIndex = 1, revision = firstRevision)

        val secondRevision = nextCustomAvatarRevision(existingRevisions = listOf(firstRevision))
        val secondName = customAvatarFileName(profileIndex = 1, revision = secondRevision)

        assertTrue(secondRevision > firstRevision)
        assertNotEquals(firstName, secondName)
    }

    @Test
    fun nextRevisionBeatsLegacyAndAllStoredRevisions() {
        // A legacy file (revision 0) plus stored revisions 1 and 4: the next
        // save must land above all of them so load keeps picking the newest.
        val next = nextCustomAvatarRevision(existingRevisions = listOf(0L, 1L, 4L))
        assertEquals(5L, next)
    }

    @Test
    fun currentFileIsTheHighestRevisionAcrossLegacyAndRevisioned() {
        val storedNames = listOf("profile_2.jpg", "profile_2_1.jpg", "profile_2_3.jpg", "profile_2_2.jpg")
        val refs = storedNames.mapNotNull(::parseCustomAvatarFileName)
        val current = refs.maxByOrNull { it.revision }
        assertEquals(CustomAvatarFileRef(profileIndex = 2, revision = 3L), current)
        assertEquals("profile_2_3.jpg", customAvatarFileName(current!!.profileIndex, current.revision))
    }

    @Test
    fun sweepKeepsFilesForExistingProfilesAndRemovesAbsentOnes() {
        val orphaned = orphanedCustomAvatarIndexes(
            storedIndexes = setOf(1, 2, 4),
            existingProfileIndexes = setOf(1, 4),
        )
        assertEquals(setOf(2), orphaned)
    }

    @Test
    fun sweepFindsNothingWhenEveryStoredIndexStillExists() {
        assertTrue(
            orphanedCustomAvatarIndexes(
                storedIndexes = setOf(1, 2),
                existingProfileIndexes = setOf(1, 2, 3),
            ).isEmpty(),
        )
        assertTrue(
            orphanedCustomAvatarIndexes(
                storedIndexes = emptySet(),
                existingProfileIndexes = setOf(1),
            ).isEmpty(),
        )
    }

    @Test
    fun sweepRemovesEverythingWhenNoProfilesRemain() {
        assertEquals(
            setOf(1, 2),
            orphanedCustomAvatarIndexes(
                storedIndexes = setOf(1, 2),
                existingProfileIndexes = emptySet(),
            ),
        )
    }
}
