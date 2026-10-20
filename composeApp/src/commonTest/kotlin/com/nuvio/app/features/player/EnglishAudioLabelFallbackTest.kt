package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classification tests for [isEnglishAudioTrack], the predicate behind the
 * player's English-audio label fallback (M-P3). The fallback exists for
 * untagged tracks; its label test must require actual English evidence —
 * a bare "dub" keyword says nothing about the dub's language.
 */
class EnglishAudioLabelFallbackTest {
    private fun track(label: String, language: String? = null) =
        AudioTrack(index = 0, id = "1", label = label, language = language)

    // --- Untagged tracks: non-English dubs must NOT classify as English ---

    @Test
    fun untaggedJapaneseDubIsNotEnglish() {
        assertFalse(isEnglishAudioTrack(track("Japanese Dub", language = "und")))
        assertFalse(isEnglishAudioTrack(track("Japanese Dub", language = null)))
    }

    @Test
    fun untaggedHindiDubIsNotEnglish() {
        assertFalse(isEnglishAudioTrack(track("Hindi Dub", language = "und")))
    }

    @Test
    fun bareDubLabelsCarryNoLanguageEvidence() {
        assertFalse(isEnglishAudioTrack(track("Dub", language = "und")))
        assertFalse(isEnglishAudioTrack(track("Dubs", language = "und")))
        assertFalse(isEnglishAudioTrack(track("Dubbed", language = "und")))
        assertFalse(isEnglishAudioTrack(track("Dub", language = null)))
    }

    @Test
    fun untaggedLabelWithoutAnyKeywordIsNotEnglish() {
        assertFalse(isEnglishAudioTrack(track("Track 1", language = "und")))
        assertFalse(isEnglishAudioTrack(track("Stereo AAC", language = null)))
    }

    // --- Untagged tracks: real English evidence still classifies as English ---

    @Test
    fun untaggedEnglishDubIsEnglish() {
        assertTrue(isEnglishAudioTrack(track("English Dub", language = "und")))
        assertTrue(isEnglishAudioTrack(track("English Dub", language = null)))
    }

    @Test
    fun untaggedEnglishLabelsAreEnglish() {
        assertTrue(isEnglishAudioTrack(track("English", language = "und")))
        assertTrue(isEnglishAudioTrack(track("English (CC)", language = "und")))
        assertTrue(isEnglishAudioTrack(track("Track 2 (AAC) - English Dub", language = "und")))
        assertTrue(isEnglishAudioTrack(track("Dubbed in English", language = "und")))
    }

    @Test
    fun untaggedEngLabelsAreEnglish() {
        assertTrue(isEnglishAudioTrack(track("ENG", language = "und")))
        assertTrue(isEnglishAudioTrack(track("ENG Dub", language = "und")))
        assertTrue(isEnglishAudioTrack(track("eng", language = null)))
    }

    // --- Tagged tracks: the tag decides; the label is never consulted ---

    @Test
    fun recognizedNonEnglishTagBeatsMisleadingEnglishLabel() {
        assertFalse(isEnglishAudioTrack(track("English Dub", language = "ja")))
        assertFalse(isEnglishAudioTrack(track("English Dub", language = "jpn")))
        assertFalse(isEnglishAudioTrack(track("English Dub", language = "fr")))
    }

    @Test
    fun englishTagWinsRegardlessOfLabel() {
        assertTrue(isEnglishAudioTrack(track("Japanese Dub", language = "en")))
        assertTrue(isEnglishAudioTrack(track("Track 1", language = "en-US")))
        assertTrue(isEnglishAudioTrack(track("Track 1", language = "eng")))
    }
}
