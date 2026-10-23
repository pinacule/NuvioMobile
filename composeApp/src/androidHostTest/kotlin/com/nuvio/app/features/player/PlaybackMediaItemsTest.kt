package com.nuvio.app.features.player

import androidx.media3.common.MimeTypes
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackMediaItemsTest {
    @Test
    fun localFileWithGuessedMp4ExtensionGetsNoForcedMime() {
        // Download files are named with an extension guessed from the source
        // URL (mp4 when unknown), even when the bytes are actually Matroska.
        // No MIME must be forced so the extractor sniffs the real container.
        assertNull(
            playbackMediaItemFromUrl("file:///storage/emulated/0/Download/movie.mp4")
                .localConfiguration?.mimeType,
        )
        assertNull(
            playbackMediaItemFromUrl("content://downloads/movie.mp4")
                .localConfiguration?.mimeType,
        )
        assertNull(
            playbackMediaItemFromUrl("/storage/emulated/0/Download/movie.mp4")
                .localConfiguration?.mimeType,
        )
    }

    @Test
    fun remoteMkvKeepsInferredMatroskaMime() {
        assertEquals(
            MimeTypes.VIDEO_MATROSKA,
            playbackMediaItemFromUrl("https://cdn.example.com/movie.mkv")
                .localConfiguration?.mimeType,
        )
        assertEquals(
            MimeTypes.VIDEO_MP4,
            playbackMediaItemFromUrl("https://cdn.example.com/movie.mp4")
                .localConfiguration?.mimeType,
        )
    }

    @Test
    fun localFileStillHonoursExplicitStreamType() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            playbackMediaItemFromUrl(
                url = "file:///storage/emulated/0/Download/movie.mp4",
                streamType = "hls",
            ).localConfiguration?.mimeType,
        )
    }
}
