package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeContinueWatchingArtworkTest {
    @Test
    fun inProgressEpisodeThumbnailRemainsBlurredUntilWatched() {
        val item = item(progressFraction = 0.5f)

        assertTrue(
            item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                artworkUrl = "thumb.jpg",
            ),
        )
    }

    @Test
    fun completedEpisodeThumbnailIsNotBlurred() {
        val item = item(progressFraction = 0.9f)

        assertFalse(
            item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                artworkUrl = "thumb.jpg",
            ),
        )
    }

    @Test
    fun fallbackArtworkIsNotBlurred() {
        val item = item(progressFraction = 0.5f)

        assertFalse(
            item.shouldBlurContinueWatchingArtwork(
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                artworkUrl = "backdrop.jpg",
            ),
        )
    }

    @Test
    fun cardQueueInProgressWithThumbnailsOrdersThumbBackdropPosterImage() {
        val item = artworkItem(
            rawPosterUrl = "raw-poster.jpg",
            rawBackgroundUrl = "raw-backdrop.jpg",
        )

        assertEquals(
            listOf("thumb.jpg", "backdrop.jpg", "raw-backdrop.jpg", "poster.jpg", "raw-poster.jpg", "image.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueueNextUpBackdropPreferredLeadsWithBackdrop() {
        val item = artworkItem(
            isNextUp = true,
            rawPosterUrl = "raw-poster.jpg",
            rawBackgroundUrl = "raw-backdrop.jpg",
        )

        assertEquals(
            listOf("backdrop.jpg", "raw-backdrop.jpg", "poster.jpg", "raw-poster.jpg", "thumb.jpg", "image.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = true,
            ),
        )
    }

    @Test
    fun cardQueueNextUpWithThumbnailsLeadsWithThumbnail() {
        val item = artworkItem(isNextUp = true)

        assertEquals(
            listOf("thumb.jpg", "backdrop.jpg", "poster.jpg", "image.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueueNextUpPlainLeadsWithBackdrop() {
        val item = artworkItem(isNextUp = true)

        assertEquals(
            listOf("backdrop.jpg", "poster.jpg", "thumb.jpg", "image.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = false,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueueDeduplicatesImageUrlEqualToPoster() {
        val item = artworkItem(imageUrl = "poster.jpg")

        assertEquals(
            listOf("thumb.jpg", "backdrop.jpg", "poster.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueueFiltersBlankCandidates() {
        val item = artworkItem(episodeThumbnail = "  ", background = null, imageUrl = "")

        assertEquals(
            listOf("poster.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueueRawVariantOnlyWhenDistinctFromCandidate() {
        val item = artworkItem(
            rawPosterUrl = "poster.jpg",
            rawBackgroundUrl = "backdrop.jpg",
        )

        assertEquals(
            listOf("thumb.jpg", "backdrop.jpg", "poster.jpg", "image.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueueSingleUrlWhenOnlyThumbnailExists() {
        val item = artworkItem(poster = null, background = null, imageUrl = null)

        assertEquals(
            listOf("thumb.jpg"),
            item.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ),
        )
    }

    @Test
    fun cardQueuePrimaryMatchesLegacyFirstChoice() {
        val nextUp = artworkItem(isNextUp = true)

        assertEquals(
            "backdrop.jpg",
            nextUp.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = false,
                preferBackdropForNextUp = false,
            ).firstOrNull(),
        )
        assertEquals(
            "thumb.jpg",
            nextUp.continueWatchingCardArtworkUrls(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            ).firstOrNull(),
        )
    }

    @Test
    fun artworkQueueInProgressWithThumbnailsLeadsWithThumbnail() {
        val item = artworkItem()

        assertEquals(
            listOf("thumb.jpg", "poster.jpg", "backdrop.jpg", "image.jpg"),
            item.continueWatchingArtworkUrls(useEpisodeThumbnails = true),
        )
    }

    @Test
    fun posterQueueForEpisodeLeadsWithPosterAndKeepsImageAheadOfThumbnail() {
        val item = artworkItem(
            rawPosterUrl = "raw-poster.jpg",
            rawBackgroundUrl = "raw-backdrop.jpg",
        )

        assertEquals(
            listOf("poster.jpg", "raw-poster.jpg", "backdrop.jpg", "raw-backdrop.jpg", "image.jpg", "thumb.jpg"),
            item.continueWatchingPosterArtworkUrls(useEpisodeThumbnails = true),
        )
    }

    @Test
    fun posterQueueWithoutEpisodeFallsBackToArtworkOrder() {
        val item = artworkItem(seasonNumber = null, episodeNumber = null)

        assertEquals(
            listOf("thumb.jpg", "poster.jpg", "backdrop.jpg", "image.jpg"),
            item.continueWatchingPosterArtworkUrls(useEpisodeThumbnails = true),
        )
    }

    private fun artworkItem(
        episodeThumbnail: String? = "thumb.jpg",
        poster: String? = "poster.jpg",
        background: String? = "backdrop.jpg",
        imageUrl: String? = "image.jpg",
        rawPosterUrl: String? = null,
        rawBackgroundUrl: String? = null,
        isNextUp: Boolean = false,
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 1,
    ) = ContinueWatchingItem(
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:1",
        title = "Show",
        subtitle = "S1 E1",
        imageUrl = imageUrl,
        poster = poster,
        background = background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeThumbnail = episodeThumbnail,
        isNextUp = isNextUp,
        resumePositionMs = 300_000L,
        durationMs = 600_000L,
        progressFraction = 0.5f,
        rawPosterUrl = rawPosterUrl,
        rawBackgroundUrl = rawBackgroundUrl,
    )

    private fun item(progressFraction: Float) = ContinueWatchingItem(
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = "show:1:1",
        title = "Show",
        subtitle = "S1 E1",
        imageUrl = "thumb.jpg",
        poster = "poster.jpg",
        background = "backdrop.jpg",
        seasonNumber = 1,
        episodeNumber = 1,
        episodeThumbnail = "thumb.jpg",
        resumePositionMs = 300_000L,
        durationMs = 600_000L,
        progressFraction = progressFraction,
    )
}
