package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomProfileAvatarResolutionTest {

    private val catalogAvatar = AvatarCatalogItem(
        id = "catalog-1",
        displayName = "Catalog",
        storagePath = "catalog/one.png",
    )

    @Test
    fun customPictureWinsOverAvatarUrlAndCatalog() {
        val profile = NuvioProfile(
            profileIndex = 1,
            name = "Pin",
            avatarId = "catalog-1",
            avatarUrl = "https://example.com/avatar.png",
        )
        assertEquals(
            "file:///data/custom.jpg",
            resolveProfileAvatarImageUrl("file:///data/custom.jpg", profile, catalogAvatar),
        )
    }

    @Test
    fun avatarUrlWinsOverCatalogWhenNoCustomPicture() {
        val profile = NuvioProfile(
            profileIndex = 1,
            avatarId = "catalog-1",
            avatarUrl = "https://example.com/avatar.png",
        )
        assertEquals(
            "https://example.com/avatar.png",
            resolveProfileAvatarImageUrl(null, profile, catalogAvatar),
        )
    }

    @Test
    fun catalogImageUsedWhenNoCustomPictureOrUrl() {
        val localCatalogAvatar = catalogAvatar.copy(localImageUrl = "file:///member/avatar.png")
        val profile = NuvioProfile(
            profileIndex = 1,
            avatarId = "catalog-1",
        )
        assertEquals(
            "file:///member/avatar.png",
            resolveProfileAvatarImageUrl(null, profile, localCatalogAvatar),
        )
    }

    @Test
    fun blankCustomPictureFallsThroughToAvatarUrl() {
        val profile = NuvioProfile(
            profileIndex = 1,
            avatarUrl = "https://example.com/avatar.png",
        )
        assertEquals(
            "https://example.com/avatar.png",
            resolveProfileAvatarImageUrl("  ", profile, null),
        )
    }

    @Test
    fun noPictureAnywhereResolvesToNullForLetterFallback() {
        val profile = NuvioProfile(
            profileIndex = 1,
            name = "Pin",
        )
        assertNull(resolveProfileAvatarImageUrl(null, profile, null))
    }
}
