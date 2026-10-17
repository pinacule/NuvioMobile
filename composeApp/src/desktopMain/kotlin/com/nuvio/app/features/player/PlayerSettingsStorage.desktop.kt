package com.nuvio.app.features.player

import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncFloat
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.decodeSyncStringSet
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncFloat
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.sync.encodeSyncStringSet
import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object PlayerSettingsStorage {
    private const val pendingExternalPlaybackKey = "pending_external_playback"
    private const val playbackBrightnessKey = "playback_brightness"
    private const val useLegacyPlayerLayoutKey = "use_legacy_player_layout"
    private const val showLoadingOverlayKey = "show_loading_overlay"
    private const val showPlayerLoadingStatusKey = "show_player_loading_status"
    private const val pauseOverlayEnabledKey = "pause_overlay_enabled"
    private const val showParentalGuideKey = "show_parental_guide"
    private const val resizeModeKey = "resize_mode"
    private const val holdToSpeedEnabledKey = "hold_to_speed_enabled"
    private const val holdToSpeedValueKey = "hold_to_speed_value"
    private const val defaultPlaybackSpeedKey = "default_playback_speed"
    private const val touchGesturesEnabledKey = "touch_gestures_enabled"
    private const val externalPlayerEnabledKey = "external_player_enabled"
    private const val externalPlayerForwardSubtitlesKey = "external_player_forward_subtitles"
    private const val externalPlayerSendSkipSegmentsKey = "external_player_send_skip_segments"
    private const val externalPlayerIdKey = "external_player_id"
    private const val preferredAudioLanguageKey = "preferred_audio_language"
    private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
    private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
    private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
    private const val subtitleTextColorKey = "subtitle_text_color"
    private const val subtitleBackgroundColorKey = "subtitle_background_color"
    private const val subtitleOutlineColorKey = "subtitle_outline_color"
    private const val subtitleOutlineEnabledKey = "subtitle_outline_enabled"
    private const val subtitleOutlineWidthKey = "subtitle_outline_width"
    private const val subtitleBoldKey = "subtitle_bold"
    private const val subtitleFontSizeSpKey = "subtitle_font_size_sp"
    private const val subtitleBottomOffsetKey = "subtitle_bottom_offset"
    private const val subtitleStripSdhKey = "subtitle_strip_sdh"
    private const val subtitleUseForcedSubtitlesKey = "subtitle_use_forced_subtitles"
    private const val subtitleShowOnlyPreferredLanguagesKey = "subtitle_show_only_preferred_languages"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"
    private const val androidPlaybackEngineKey = "android_playback_engine"
    private const val androidLibmpvVideoOutputKey = "android_libmpv_video_output"
    private const val androidLibmpvHardwareDecodingEnabledKey = "android_libmpv_hardware_decoding_enabled"
    private const val androidLibmpvYuv420pEnabledKey = "android_libmpv_yuv420p_enabled"
    private const val decoderPriorityKey = "decoder_priority"
    private const val mapDV7ToHevcKey = "map_dv7_to_hevc"
    private const val tunnelingEnabledKey = "tunneling_enabled"
    private const val exoNativeMemoryEnabledKey = "exo_native_memory_enabled"
    private const val vodCacheEnabledKey = "vod_cache_enabled"
    private const val vodCacheSizeModeKey = "vod_cache_size_mode"
    private const val vodCacheSizeMbKey = "vod_cache_size_mb"
    private const val bufferEngineEnabledKey = "buffer_engine_enabled"
    private const val minBufferMsKey = "min_buffer_ms"
    private const val maxBufferMsKey = "max_buffer_ms"
    private const val bufferForPlaybackMsKey = "buffer_for_playback_ms"
    private const val bufferForPlaybackAfterRebufferMsKey = "buffer_for_playback_after_rebuffer_ms"
    private const val backBufferDurationMsKey = "back_buffer_duration_ms"
    private const val targetBufferSizeMbKey = "target_buffer_size_mb"
    private const val streamAutoPlayModeKey = "stream_auto_play_mode"
    private const val streamAutoPlaySourceKey = "stream_auto_play_source"
    private const val streamAutoPlaySelectedAddonsKey = "stream_auto_play_selected_addons"
    private const val streamAutoPlaySelectedPluginsKey = "stream_auto_play_selected_plugins"
    private const val streamAutoPlayRegexKey = "stream_auto_play_regex"
    private const val streamAutoPlayTimeoutSecondsKey = "stream_auto_play_timeout_seconds"
    private const val skipIntroEnabledKey = "skip_intro_enabled"
    private const val autoSkipMovieCreditsKey = "auto_skip_movie_credits"
    private const val autoSkipSegmentTypesKey = "auto_skip_segment_types"
    private const val autoSkipPostCreditsKey = "auto_skip_post_credits"
    private const val animeSkipEnabledKey = "animeskip_enabled"
    private const val animeSkipClientIdKey = "animeskip_client_id"
    private const val introDbApiKeyKey = "introdb_api_key"
    private const val introSubmitEnabledKey = "intro_submit_enabled"
    private const val streamAutoPlayNextEpisodeEnabledKey = "stream_auto_play_next_episode_enabled"
    private const val streamAutoPlayNextEpisodeFallbackEnabledKey = "stream_auto_play_next_episode_fallback_enabled"
    private const val streamAutoPlayPreferBingeGroupKey = "stream_auto_play_prefer_binge_group"
    private const val streamAutoPlayReuseBingeGroupKey = "stream_auto_play_reuse_binge_group"
    private const val nextEpisodeThresholdModeKey = "next_episode_threshold_mode"
    private const val nextEpisodeThresholdPercentKey = "next_episode_threshold_percent_v2"
    private const val nextEpisodeThresholdMinutesBeforeEndKey = "next_episode_threshold_minutes_before_end_v2"
    private const val preloadNextEpisodeSourcesKey = "preload_next_episode_sources"
    private const val useLibassKey = "use_libass"
    private const val libassRenderTypeKey = "libass_render_type"
    private const val iosVideoOutputPresetKey = "ios_video_output_preset"
    private const val iosToneMappingModeKey = "ios_tone_mapping_mode"
    private const val iosTargetPrimariesKey = "ios_target_primaries"
    private const val iosTargetTransferKey = "ios_target_transfer"
    private const val iosHardwareDecoderModeKey = "ios_hardware_decoder_mode"
    private const val iosAudioOutputModeKey = "ios_audio_output_mode"
    private const val iosExtendedDynamicRangeEnabledKey = "ios_extended_dynamic_range_enabled"
    private const val iosTargetColorspaceHintEnabledKey = "ios_target_colorspace_hint_enabled"
    private const val iosHdrComputePeakEnabledKey = "ios_hdr_compute_peak_enabled"
    private const val iosDebandEnabledKey = "ios_deband_enabled"
    private const val iosInterpolationEnabledKey = "ios_interpolation_enabled"
    private const val iosBrightnessKey = "ios_brightness"
    private const val iosContrastKey = "ios_contrast"
    private const val iosSaturationKey = "ios_saturation"
    private const val iosGammaKey = "ios_gamma"
    private val syncKeys = listOf(
        showLoadingOverlayKey,
        showPlayerLoadingStatusKey,
        pauseOverlayEnabledKey,
        showParentalGuideKey,
        resizeModeKey,
        holdToSpeedEnabledKey,
        holdToSpeedValueKey,
        touchGesturesEnabledKey,
        externalPlayerEnabledKey,
        externalPlayerForwardSubtitlesKey,
        externalPlayerSendSkipSegmentsKey,
        externalPlayerIdKey,
        preferredAudioLanguageKey,
        secondaryPreferredAudioLanguageKey,
        preferredSubtitleLanguageKey,
        secondaryPreferredSubtitleLanguageKey,
        subtitleTextColorKey,
        subtitleBackgroundColorKey,
        subtitleOutlineColorKey,
        subtitleOutlineEnabledKey,
        subtitleOutlineWidthKey,
        subtitleBoldKey,
        subtitleFontSizeSpKey,
        subtitleBottomOffsetKey,
        subtitleStripSdhKey,
        subtitleUseForcedSubtitlesKey,
        subtitleShowOnlyPreferredLanguagesKey,
        streamReuseLastLinkEnabledKey,
        streamReuseLastLinkCacheHoursKey,
        androidPlaybackEngineKey,
        androidLibmpvVideoOutputKey,
        androidLibmpvHardwareDecodingEnabledKey,
        androidLibmpvYuv420pEnabledKey,
        decoderPriorityKey,
        mapDV7ToHevcKey,
        tunnelingEnabledKey,
        streamAutoPlayModeKey,
        streamAutoPlaySourceKey,
        streamAutoPlaySelectedAddonsKey,
        streamAutoPlaySelectedPluginsKey,
        streamAutoPlayRegexKey,
        streamAutoPlayTimeoutSecondsKey,
        skipIntroEnabledKey,
        autoSkipMovieCreditsKey,
        autoSkipSegmentTypesKey,
        autoSkipPostCreditsKey,
        animeSkipEnabledKey,
        animeSkipClientIdKey,
        streamAutoPlayNextEpisodeEnabledKey,
        streamAutoPlayNextEpisodeFallbackEnabledKey,
        streamAutoPlayPreferBingeGroupKey,
        streamAutoPlayReuseBingeGroupKey,
        nextEpisodeThresholdModeKey,
        nextEpisodeThresholdPercentKey,
        nextEpisodeThresholdMinutesBeforeEndKey,
        preloadNextEpisodeSourcesKey,
        useLibassKey,
        libassRenderTypeKey,
        iosVideoOutputPresetKey,
        iosToneMappingModeKey,
        iosTargetPrimariesKey,
        iosTargetTransferKey,
        iosHardwareDecoderModeKey,
        iosAudioOutputModeKey,
        iosExtendedDynamicRangeEnabledKey,
        iosTargetColorspaceHintEnabledKey,
        iosHdrComputePeakEnabledKey,
        iosDebandEnabledKey,
        iosInterpolationEnabledKey,
        iosBrightnessKey,
        iosContrastKey,
        iosSaturationKey,
        iosGammaKey,
    )

    private val prefs = DesktopPreferences("player_settings")

    actual fun loadPendingExternalPlayback(): String? = prefs.getString(pendingExternalPlaybackKey)

    actual fun savePendingExternalPlayback(value: String?) {
        if (value == null) prefs.remove(pendingExternalPlaybackKey) else prefs.putString(pendingExternalPlaybackKey, value)
    }

    actual fun loadPlaybackBrightness(): Float? =
        prefs.getFloat(ProfileScopedKey.of(playbackBrightnessKey))

    actual fun savePlaybackBrightness(level: Float) {
        prefs.putFloat(ProfileScopedKey.of(playbackBrightnessKey), level)
    }

    actual fun loadShowLoadingOverlay(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(showLoadingOverlayKey))

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(showLoadingOverlayKey), enabled)
    }

    actual fun loadShowPlayerLoadingStatus(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(showPlayerLoadingStatusKey))

    actual fun saveShowPlayerLoadingStatus(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(showPlayerLoadingStatusKey), enabled)
    }

    actual fun loadPauseOverlayEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(pauseOverlayEnabledKey))

    actual fun savePauseOverlayEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(pauseOverlayEnabledKey), enabled)
    }

    actual fun loadUseLegacyPlayerLayout(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(useLegacyPlayerLayoutKey))

    actual fun saveUseLegacyPlayerLayout(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useLegacyPlayerLayoutKey), enabled)
    }

    actual fun loadShowParentalGuide(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(showParentalGuideKey))

    actual fun saveShowParentalGuide(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(showParentalGuideKey), enabled)
    }

    actual fun loadResizeMode(): String? =
        prefs.getString(ProfileScopedKey.of(resizeModeKey))

    actual fun saveResizeMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(resizeModeKey), mode)
    }

    actual fun loadHoldToSpeedEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(holdToSpeedEnabledKey))

    actual fun saveHoldToSpeedEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(holdToSpeedEnabledKey), enabled)
    }

    actual fun loadHoldToSpeedValue(): Float? =
        prefs.getFloat(ProfileScopedKey.of(holdToSpeedValueKey))

    actual fun saveHoldToSpeedValue(speed: Float) {
        prefs.putFloat(ProfileScopedKey.of(holdToSpeedValueKey), speed)
    }

    actual fun loadDefaultPlaybackSpeed(): Float? =
        prefs.getFloat(ProfileScopedKey.of(defaultPlaybackSpeedKey))

    actual fun saveDefaultPlaybackSpeed(speed: Float) {
        prefs.putFloat(ProfileScopedKey.of(defaultPlaybackSpeedKey), speed)
    }

    actual fun loadTouchGesturesEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(touchGesturesEnabledKey))

    actual fun saveTouchGesturesEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(touchGesturesEnabledKey), enabled)
    }

    actual fun loadExternalPlayerEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(externalPlayerEnabledKey))

    actual fun saveExternalPlayerEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(externalPlayerEnabledKey), enabled)
    }

    actual fun loadExternalPlayerForwardSubtitles(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(externalPlayerForwardSubtitlesKey))

    actual fun saveExternalPlayerForwardSubtitles(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(externalPlayerForwardSubtitlesKey), enabled)
    }

    actual fun loadExternalPlayerSendSkipSegments(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(externalPlayerSendSkipSegmentsKey))

    actual fun saveExternalPlayerSendSkipSegments(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(externalPlayerSendSkipSegmentsKey), enabled)
    }

    actual fun loadExternalPlayerId(): String? =
        prefs.getString(ProfileScopedKey.of(externalPlayerIdKey))

    actual fun saveExternalPlayerId(playerId: String?) {
        val key = ProfileScopedKey.of(externalPlayerIdKey)
        if (playerId.isNullOrBlank()) {
            prefs.remove(key)
        } else {
            prefs.putString(key, playerId)
        }
    }

    actual fun loadPreferredAudioLanguage(): String? =
        prefs.getString(ProfileScopedKey.of(preferredAudioLanguageKey))

    actual fun savePreferredAudioLanguage(language: String) {
        prefs.putString(ProfileScopedKey.of(preferredAudioLanguageKey), language)
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? =
        prefs.getString(ProfileScopedKey.of(secondaryPreferredAudioLanguageKey))

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        val key = ProfileScopedKey.of(secondaryPreferredAudioLanguageKey)
        if (language.isNullOrBlank()) {
            prefs.remove(key)
        } else {
            prefs.putString(key, language)
        }
    }

    actual fun loadPreferredSubtitleLanguage(): String? =
        prefs.getString(ProfileScopedKey.of(preferredSubtitleLanguageKey))

    actual fun savePreferredSubtitleLanguage(language: String) {
        prefs.putString(ProfileScopedKey.of(preferredSubtitleLanguageKey), language)
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? =
        prefs.getString(ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey))

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        val key = ProfileScopedKey.of(secondaryPreferredSubtitleLanguageKey)
        if (language.isNullOrBlank()) {
            prefs.remove(key)
        } else {
            prefs.putString(key, language)
        }
    }

    actual fun loadSubtitleTextColor(): String? =
        prefs.getString(ProfileScopedKey.of(subtitleTextColorKey))

    actual fun saveSubtitleTextColor(colorHex: String) {
        prefs.putString(ProfileScopedKey.of(subtitleTextColorKey), colorHex)
    }

    actual fun loadSubtitleBackgroundColor(): String? =
        prefs.getString(ProfileScopedKey.of(subtitleBackgroundColorKey))

    actual fun saveSubtitleBackgroundColor(colorHex: String) {
        prefs.putString(ProfileScopedKey.of(subtitleBackgroundColorKey), colorHex)
    }

    actual fun loadSubtitleOutlineColor(): String? =
        prefs.getString(ProfileScopedKey.of(subtitleOutlineColorKey))

    actual fun saveSubtitleOutlineColor(colorHex: String) {
        prefs.putString(ProfileScopedKey.of(subtitleOutlineColorKey), colorHex)
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(subtitleOutlineEnabledKey))

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(subtitleOutlineEnabledKey), enabled)
    }

    actual fun loadSubtitleOutlineWidth(): Int? =
        prefs.getInt(ProfileScopedKey.of(subtitleOutlineWidthKey))

    actual fun saveSubtitleOutlineWidth(width: Int) {
        prefs.putInt(ProfileScopedKey.of(subtitleOutlineWidthKey), width)
    }

    actual fun loadSubtitleBold(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(subtitleBoldKey))

    actual fun saveSubtitleBold(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(subtitleBoldKey), enabled)
    }

    actual fun loadSubtitleFontSizeSp(): Int? =
        prefs.getInt(ProfileScopedKey.of(subtitleFontSizeSpKey))

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        prefs.putInt(ProfileScopedKey.of(subtitleFontSizeSpKey), fontSizeSp)
    }

    actual fun loadSubtitleBottomOffset(): Int? =
        prefs.getInt(ProfileScopedKey.of(subtitleBottomOffsetKey))

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        prefs.putInt(ProfileScopedKey.of(subtitleBottomOffsetKey), bottomOffset)
    }

    actual fun loadSubtitleStripSdh(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(subtitleStripSdhKey))

    actual fun saveSubtitleStripSdh(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(subtitleStripSdhKey), enabled)
    }

    actual fun loadSubtitleUseForcedSubtitles(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(subtitleUseForcedSubtitlesKey))

    actual fun saveSubtitleUseForcedSubtitles(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(subtitleUseForcedSubtitlesKey), enabled)
    }

    actual fun loadSubtitleShowOnlyPreferredLanguages(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(subtitleShowOnlyPreferredLanguagesKey))

    actual fun saveSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(subtitleShowOnlyPreferredLanguagesKey), enabled)
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(streamReuseLastLinkEnabledKey))

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(streamReuseLastLinkEnabledKey), enabled)
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? =
        prefs.getInt(ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey))

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        prefs.putInt(ProfileScopedKey.of(streamReuseLastLinkCacheHoursKey), hours)
    }

    actual fun loadAndroidPlaybackEngine(): String? =
        prefs.getString(ProfileScopedKey.of(androidPlaybackEngineKey))

    actual fun saveAndroidPlaybackEngine(engine: String) {
        prefs.putString(ProfileScopedKey.of(androidPlaybackEngineKey), engine)
    }

    actual fun loadAndroidLibmpvVideoOutput(): String? =
        prefs.getString(ProfileScopedKey.of(androidLibmpvVideoOutputKey))

    actual fun saveAndroidLibmpvVideoOutput(output: String) {
        prefs.putString(ProfileScopedKey.of(androidLibmpvVideoOutputKey), output)
    }

    actual fun loadAndroidLibmpvHardwareDecodingEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(androidLibmpvHardwareDecodingEnabledKey))

    actual fun saveAndroidLibmpvHardwareDecodingEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(androidLibmpvHardwareDecodingEnabledKey), enabled)
    }

    actual fun loadAndroidLibmpvYuv420pEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(androidLibmpvYuv420pEnabledKey))

    actual fun saveAndroidLibmpvYuv420pEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(androidLibmpvYuv420pEnabledKey), enabled)
    }

    actual fun loadDecoderPriority(): Int? =
        prefs.getInt(ProfileScopedKey.of(decoderPriorityKey))

    actual fun saveDecoderPriority(priority: Int) {
        prefs.putInt(ProfileScopedKey.of(decoderPriorityKey), priority)
    }

    actual fun loadMapDV7ToHevc(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(mapDV7ToHevcKey))

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(mapDV7ToHevcKey), enabled)
    }

    actual fun loadTunnelingEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(tunnelingEnabledKey))

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(tunnelingEnabledKey), enabled)
    }

    actual fun loadExoNativeMemoryEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(exoNativeMemoryEnabledKey))

    actual fun saveExoNativeMemoryEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(exoNativeMemoryEnabledKey), enabled)
    }

    actual fun loadVodCacheEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(vodCacheEnabledKey))

    actual fun saveVodCacheEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(vodCacheEnabledKey), enabled)
    }

    actual fun loadVodCacheSizeMode(): String? =
        prefs.getString(ProfileScopedKey.of(vodCacheSizeModeKey))

    actual fun saveVodCacheSizeMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(vodCacheSizeModeKey), mode)
    }

    actual fun loadVodCacheSizeMb(): Int? =
        prefs.getInt(ProfileScopedKey.of(vodCacheSizeMbKey))

    actual fun saveVodCacheSizeMb(sizeMb: Int) {
        prefs.putInt(
            ProfileScopedKey.of(vodCacheSizeMbKey),
            sizeMb.coerceIn(VodCacheSizing.MIN_SIZE_MB, VodCacheSizing.MAX_SIZE_MB),
        )
    }

    actual fun loadBufferEngineEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(bufferEngineEnabledKey))

    actual fun saveBufferEngineEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(bufferEngineEnabledKey), enabled)
    }

    actual fun loadMinBufferMs(): Int? = loadStoredInt(minBufferMsKey)

    actual fun saveMinBufferMs(value: Int) = saveStoredInt(minBufferMsKey, value)

    actual fun loadMaxBufferMs(): Int? = loadStoredInt(maxBufferMsKey)

    actual fun saveMaxBufferMs(value: Int) = saveStoredInt(maxBufferMsKey, value)

    actual fun loadBufferForPlaybackMs(): Int? = loadStoredInt(bufferForPlaybackMsKey)

    actual fun saveBufferForPlaybackMs(value: Int) = saveStoredInt(bufferForPlaybackMsKey, value)

    actual fun loadBufferForPlaybackAfterRebufferMs(): Int? = loadStoredInt(bufferForPlaybackAfterRebufferMsKey)

    actual fun saveBufferForPlaybackAfterRebufferMs(value: Int) = saveStoredInt(bufferForPlaybackAfterRebufferMsKey, value)

    actual fun loadBackBufferDurationMs(): Int? = loadStoredInt(backBufferDurationMsKey)

    actual fun saveBackBufferDurationMs(value: Int) = saveStoredInt(backBufferDurationMsKey, value)

    actual fun loadTargetBufferSizeMb(): Int? = loadStoredInt(targetBufferSizeMbKey)

    actual fun saveTargetBufferSizeMb(value: Int) = saveStoredInt(targetBufferSizeMbKey, value)

    private fun loadStoredInt(keyBase: String): Int? =
        prefs.getInt(ProfileScopedKey.of(keyBase))

    private fun saveStoredInt(keyBase: String, value: Int) {
        prefs.putInt(ProfileScopedKey.of(keyBase), value)
    }

    actual fun loadStreamAutoPlayMode(): String? =
        prefs.getString(ProfileScopedKey.of(streamAutoPlayModeKey))

    actual fun saveStreamAutoPlayMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(streamAutoPlayModeKey), mode)
    }

    actual fun loadStreamAutoPlaySource(): String? =
        prefs.getString(ProfileScopedKey.of(streamAutoPlaySourceKey))

    actual fun saveStreamAutoPlaySource(source: String) {
        prefs.putString(ProfileScopedKey.of(streamAutoPlaySourceKey), source)
    }

    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? =
        prefs.getStringSet(ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey))

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        prefs.putStringSet(ProfileScopedKey.of(streamAutoPlaySelectedAddonsKey), addons)
    }

    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? =
        prefs.getStringSet(ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey))

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        prefs.putStringSet(ProfileScopedKey.of(streamAutoPlaySelectedPluginsKey), plugins)
    }

    actual fun loadStreamAutoPlayRegex(): String? =
        prefs.getString(ProfileScopedKey.of(streamAutoPlayRegexKey))

    actual fun saveStreamAutoPlayRegex(regex: String) {
        prefs.putString(ProfileScopedKey.of(streamAutoPlayRegexKey), regex)
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? =
        prefs.getInt(ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey))

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        prefs.putInt(ProfileScopedKey.of(streamAutoPlayTimeoutSecondsKey), seconds)
    }

    actual fun loadSkipIntroEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(skipIntroEnabledKey))

    actual fun loadAutoSkipSegmentTypes(): Set<String>? =
        prefs.getStringSet(ProfileScopedKey.of(autoSkipSegmentTypesKey))

    actual fun saveAutoSkipSegmentTypes(types: Set<String>) {
        prefs.putStringSet(ProfileScopedKey.of(autoSkipSegmentTypesKey), types)
    }

    actual fun loadAutoSkipMovieCredits(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(autoSkipMovieCreditsKey))

    actual fun saveAutoSkipMovieCredits(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(autoSkipMovieCreditsKey), enabled)
    }

    actual fun loadAutoSkipPostCredits(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(autoSkipPostCreditsKey))

    actual fun saveAutoSkipPostCredits(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(autoSkipPostCreditsKey), enabled)
    }


    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(skipIntroEnabledKey), enabled)
    }

    actual fun loadAnimeSkipEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(animeSkipEnabledKey))

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(animeSkipEnabledKey), enabled)
    }

    actual fun loadAnimeSkipClientId(): String? =
        prefs.getString(ProfileScopedKey.of(animeSkipClientIdKey))

    actual fun saveAnimeSkipClientId(clientId: String) {
        prefs.putString(ProfileScopedKey.of(animeSkipClientIdKey), clientId)
    }

    actual fun loadIntroDbApiKey(): String? =
        prefs.getString(ProfileScopedKey.of(introDbApiKeyKey))

    actual fun saveIntroDbApiKey(apiKey: String) {
        prefs.putString(ProfileScopedKey.of(introDbApiKeyKey), apiKey)
    }

    actual fun loadIntroSubmitEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(introSubmitEnabledKey))

    actual fun saveIntroSubmitEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(introSubmitEnabledKey), enabled)
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey))

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeEnabledKey), enabled)
    }

    actual fun loadStreamAutoPlayNextEpisodeFallbackEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeFallbackEnabledKey))

    actual fun saveStreamAutoPlayNextEpisodeFallbackEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(streamAutoPlayNextEpisodeFallbackEnabledKey), enabled)
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey))

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(streamAutoPlayPreferBingeGroupKey), enabled)
    }

    actual fun loadStreamAutoPlayReuseBingeGroup(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(streamAutoPlayReuseBingeGroupKey))

    actual fun saveStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(streamAutoPlayReuseBingeGroupKey), enabled)
    }

    actual fun loadNextEpisodeThresholdMode(): String? =
        prefs.getString(ProfileScopedKey.of(nextEpisodeThresholdModeKey))

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(nextEpisodeThresholdModeKey), mode)
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? =
        prefs.getFloat(ProfileScopedKey.of(nextEpisodeThresholdPercentKey))

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        prefs.putFloat(ProfileScopedKey.of(nextEpisodeThresholdPercentKey), percent)
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? =
        prefs.getFloat(ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey))

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        prefs.putFloat(ProfileScopedKey.of(nextEpisodeThresholdMinutesBeforeEndKey), minutes)
    }

    actual fun loadPreloadNextEpisodeSources(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(preloadNextEpisodeSourcesKey))

    actual fun savePreloadNextEpisodeSources(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(preloadNextEpisodeSourcesKey), enabled)
    }

    actual fun loadUseLibass(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(useLibassKey))

    actual fun saveUseLibass(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(useLibassKey), enabled)
    }

    actual fun loadLibassRenderType(): String? =
        prefs.getString(ProfileScopedKey.of(libassRenderTypeKey))

    actual fun saveLibassRenderType(renderType: String) {
        prefs.putString(ProfileScopedKey.of(libassRenderTypeKey), renderType)
    }

    actual fun loadIosVideoOutputPreset(): String? =
        prefs.getString(ProfileScopedKey.of(iosVideoOutputPresetKey))

    actual fun saveIosVideoOutputPreset(preset: String) {
        prefs.putString(ProfileScopedKey.of(iosVideoOutputPresetKey), preset)
    }

    actual fun loadIosToneMappingMode(): String? =
        prefs.getString(ProfileScopedKey.of(iosToneMappingModeKey))

    actual fun saveIosToneMappingMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(iosToneMappingModeKey), mode)
    }

    actual fun loadIosTargetPrimaries(): String? =
        prefs.getString(ProfileScopedKey.of(iosTargetPrimariesKey))

    actual fun saveIosTargetPrimaries(primaries: String) {
        prefs.putString(ProfileScopedKey.of(iosTargetPrimariesKey), primaries)
    }

    actual fun loadIosTargetTransfer(): String? =
        prefs.getString(ProfileScopedKey.of(iosTargetTransferKey))

    actual fun saveIosTargetTransfer(transfer: String) {
        prefs.putString(ProfileScopedKey.of(iosTargetTransferKey), transfer)
    }

    actual fun loadIosHardwareDecoderMode(): String? =
        prefs.getString(ProfileScopedKey.of(iosHardwareDecoderModeKey))

    actual fun saveIosHardwareDecoderMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(iosHardwareDecoderModeKey), mode)
    }

    actual fun loadIosAudioOutputMode(): String? =
        prefs.getString(ProfileScopedKey.of(iosAudioOutputModeKey))

    actual fun saveIosAudioOutputMode(mode: String) {
        prefs.putString(ProfileScopedKey.of(iosAudioOutputModeKey), mode)
    }

    private fun loadIosBoolean(keyBase: String, defaultValue: Boolean): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(keyBase))

    private fun saveIosBoolean(keyBase: String, enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(keyBase), enabled)
    }

    private fun loadIosInt(keyBase: String): Int? =
        prefs.getInt(ProfileScopedKey.of(keyBase))

    private fun saveIosInt(keyBase: String, value: Int) {
        prefs.putInt(ProfileScopedKey.of(keyBase), value)
    }

    actual fun loadIosExtendedDynamicRangeEnabled(): Boolean? =
        loadIosBoolean(iosExtendedDynamicRangeEnabledKey, true)

    actual fun saveIosExtendedDynamicRangeEnabled(enabled: Boolean) {
        saveIosBoolean(iosExtendedDynamicRangeEnabledKey, enabled)
    }

    actual fun loadIosTargetColorspaceHintEnabled(): Boolean? =
        loadIosBoolean(iosTargetColorspaceHintEnabledKey, true)

    actual fun saveIosTargetColorspaceHintEnabled(enabled: Boolean) {
        saveIosBoolean(iosTargetColorspaceHintEnabledKey, enabled)
    }

    actual fun loadIosHdrComputePeakEnabled(): Boolean? =
        loadIosBoolean(iosHdrComputePeakEnabledKey, true)

    actual fun saveIosHdrComputePeakEnabled(enabled: Boolean) {
        saveIosBoolean(iosHdrComputePeakEnabledKey, enabled)
    }

    actual fun loadIosDebandEnabled(): Boolean? =
        loadIosBoolean(iosDebandEnabledKey, false)

    actual fun saveIosDebandEnabled(enabled: Boolean) {
        saveIosBoolean(iosDebandEnabledKey, enabled)
    }

    actual fun loadIosInterpolationEnabled(): Boolean? =
        loadIosBoolean(iosInterpolationEnabledKey, false)

    actual fun saveIosInterpolationEnabled(enabled: Boolean) {
        saveIosBoolean(iosInterpolationEnabledKey, enabled)
    }

    actual fun loadIosBrightness(): Int? = loadIosInt(iosBrightnessKey)

    actual fun saveIosBrightness(value: Int) {
        saveIosInt(iosBrightnessKey, value)
    }

    actual fun loadIosContrast(): Int? = loadIosInt(iosContrastKey)

    actual fun saveIosContrast(value: Int) {
        saveIosInt(iosContrastKey, value)
    }

    actual fun loadIosSaturation(): Int? = loadIosInt(iosSaturationKey)

    actual fun saveIosSaturation(value: Int) {
        saveIosInt(iosSaturationKey, value)
    }

    actual fun loadIosGamma(): Int? = loadIosInt(iosGammaKey)

    actual fun saveIosGamma(value: Int) {
        saveIosInt(iosGammaKey, value)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadShowLoadingOverlay()?.let { put(showLoadingOverlayKey, encodeSyncBoolean(it)) }
        loadShowPlayerLoadingStatus()?.let { put(showPlayerLoadingStatusKey, encodeSyncBoolean(it)) }
        loadPauseOverlayEnabled()?.let { put(pauseOverlayEnabledKey, encodeSyncBoolean(it)) }
        loadShowParentalGuide()?.let { put(showParentalGuideKey, encodeSyncBoolean(it)) }
        loadResizeMode()?.let { put(resizeModeKey, encodeSyncString(it)) }
        loadHoldToSpeedEnabled()?.let { put(holdToSpeedEnabledKey, encodeSyncBoolean(it)) }
        loadHoldToSpeedValue()?.let { put(holdToSpeedValueKey, encodeSyncFloat(it)) }
        loadTouchGesturesEnabled()?.let { put(touchGesturesEnabledKey, encodeSyncBoolean(it)) }
        loadExternalPlayerEnabled()?.let { put(externalPlayerEnabledKey, encodeSyncBoolean(it)) }
        loadExternalPlayerForwardSubtitles()?.let { put(externalPlayerForwardSubtitlesKey, encodeSyncBoolean(it)) }
        loadExternalPlayerSendSkipSegments()?.let { put(externalPlayerSendSkipSegmentsKey, encodeSyncBoolean(it)) }
        loadExternalPlayerId()?.let { put(externalPlayerIdKey, encodeSyncString(it)) }
        loadPreferredAudioLanguage()?.let { put(preferredAudioLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredAudioLanguage()?.let { put(secondaryPreferredAudioLanguageKey, encodeSyncString(it)) }
        loadPreferredSubtitleLanguage()?.let { put(preferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredSubtitleLanguage()?.let { put(secondaryPreferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSubtitleTextColor()?.let { put(subtitleTextColorKey, encodeSyncString(it)) }
        loadSubtitleBackgroundColor()?.let { put(subtitleBackgroundColorKey, encodeSyncString(it)) }
        loadSubtitleOutlineColor()?.let { put(subtitleOutlineColorKey, encodeSyncString(it)) }
        loadSubtitleOutlineEnabled()?.let { put(subtitleOutlineEnabledKey, encodeSyncBoolean(it)) }
        loadSubtitleOutlineWidth()?.let { put(subtitleOutlineWidthKey, encodeSyncInt(it)) }
        loadSubtitleBold()?.let { put(subtitleBoldKey, encodeSyncBoolean(it)) }
        loadSubtitleFontSizeSp()?.let { put(subtitleFontSizeSpKey, encodeSyncInt(it)) }
        loadSubtitleBottomOffset()?.let { put(subtitleBottomOffsetKey, encodeSyncInt(it)) }
        loadSubtitleStripSdh()?.let { put(subtitleStripSdhKey, encodeSyncBoolean(it)) }
        loadSubtitleUseForcedSubtitles()?.let { put(subtitleUseForcedSubtitlesKey, encodeSyncBoolean(it)) }
        loadSubtitleShowOnlyPreferredLanguages()?.let { put(subtitleShowOnlyPreferredLanguagesKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkEnabled()?.let { put(streamReuseLastLinkEnabledKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkCacheHours()?.let { put(streamReuseLastLinkCacheHoursKey, encodeSyncInt(it)) }
        loadAndroidPlaybackEngine()?.let { put(androidPlaybackEngineKey, encodeSyncString(it)) }
        loadAndroidLibmpvVideoOutput()?.let { put(androidLibmpvVideoOutputKey, encodeSyncString(it)) }
        loadAndroidLibmpvHardwareDecodingEnabled()?.let {
            put(androidLibmpvHardwareDecodingEnabledKey, encodeSyncBoolean(it))
        }
        loadAndroidLibmpvYuv420pEnabled()?.let { put(androidLibmpvYuv420pEnabledKey, encodeSyncBoolean(it)) }
        loadDecoderPriority()?.let { put(decoderPriorityKey, encodeSyncInt(it)) }
        loadMapDV7ToHevc()?.let { put(mapDV7ToHevcKey, encodeSyncBoolean(it)) }
        loadTunnelingEnabled()?.let { put(tunnelingEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayMode()?.let { put(streamAutoPlayModeKey, encodeSyncString(it)) }
        loadStreamAutoPlaySource()?.let { put(streamAutoPlaySourceKey, encodeSyncString(it)) }
        loadStreamAutoPlaySelectedAddons()?.let { put(streamAutoPlaySelectedAddonsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlaySelectedPlugins()?.let { put(streamAutoPlaySelectedPluginsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlayRegex()?.let { put(streamAutoPlayRegexKey, encodeSyncString(it)) }
        loadStreamAutoPlayTimeoutSeconds()?.let { put(streamAutoPlayTimeoutSecondsKey, encodeSyncInt(it)) }
        loadSkipIntroEnabled()?.let { put(skipIntroEnabledKey, encodeSyncBoolean(it)) }
        loadAutoSkipMovieCredits()?.let { put(autoSkipMovieCreditsKey, encodeSyncBoolean(it)) }
        loadAutoSkipSegmentTypes()?.let { put(autoSkipSegmentTypesKey, encodeSyncStringSet(it)) }
        loadAutoSkipPostCredits()?.let { put(autoSkipPostCreditsKey, encodeSyncBoolean(it)) }
        loadAnimeSkipEnabled()?.let { put(animeSkipEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipClientId()?.let { put(animeSkipClientIdKey, encodeSyncString(it)) }
        loadStreamAutoPlayNextEpisodeEnabled()?.let { put(streamAutoPlayNextEpisodeEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayNextEpisodeFallbackEnabled()?.let { put(streamAutoPlayNextEpisodeFallbackEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayPreferBingeGroup()?.let { put(streamAutoPlayPreferBingeGroupKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayReuseBingeGroup()?.let { put(streamAutoPlayReuseBingeGroupKey, encodeSyncBoolean(it)) }
        loadNextEpisodeThresholdMode()?.let { put(nextEpisodeThresholdModeKey, encodeSyncString(it)) }
        loadNextEpisodeThresholdPercent()?.let { put(nextEpisodeThresholdPercentKey, encodeSyncFloat(it)) }
        loadNextEpisodeThresholdMinutesBeforeEnd()?.let { put(nextEpisodeThresholdMinutesBeforeEndKey, encodeSyncFloat(it)) }
        loadPreloadNextEpisodeSources()?.let { put(preloadNextEpisodeSourcesKey, encodeSyncBoolean(it)) }
        loadUseLibass()?.let { put(useLibassKey, encodeSyncBoolean(it)) }
        loadLibassRenderType()?.let { put(libassRenderTypeKey, encodeSyncString(it)) }
        loadIosVideoOutputPreset()?.let { put(iosVideoOutputPresetKey, encodeSyncString(it)) }
        loadIosToneMappingMode()?.let { put(iosToneMappingModeKey, encodeSyncString(it)) }
        loadIosTargetPrimaries()?.let { put(iosTargetPrimariesKey, encodeSyncString(it)) }
        loadIosTargetTransfer()?.let { put(iosTargetTransferKey, encodeSyncString(it)) }
        loadIosHardwareDecoderMode()?.let { put(iosHardwareDecoderModeKey, encodeSyncString(it)) }
        loadIosAudioOutputMode()?.let { put(iosAudioOutputModeKey, encodeSyncString(it)) }
        loadIosExtendedDynamicRangeEnabled()?.let { put(iosExtendedDynamicRangeEnabledKey, encodeSyncBoolean(it)) }
        loadIosTargetColorspaceHintEnabled()?.let { put(iosTargetColorspaceHintEnabledKey, encodeSyncBoolean(it)) }
        loadIosHdrComputePeakEnabled()?.let { put(iosHdrComputePeakEnabledKey, encodeSyncBoolean(it)) }
        loadIosDebandEnabled()?.let { put(iosDebandEnabledKey, encodeSyncBoolean(it)) }
        loadIosInterpolationEnabled()?.let { put(iosInterpolationEnabledKey, encodeSyncBoolean(it)) }
        loadIosBrightness()?.let { put(iosBrightnessKey, encodeSyncInt(it)) }
        loadIosContrast()?.let { put(iosContrastKey, encodeSyncInt(it)) }
        loadIosSaturation()?.let { put(iosSaturationKey, encodeSyncInt(it)) }
        loadIosGamma()?.let { put(iosGammaKey, encodeSyncInt(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { prefs.remove(ProfileScopedKey.of(it)) }

        payload.decodeSyncBoolean(showLoadingOverlayKey)?.let(::saveShowLoadingOverlay)
        payload.decodeSyncBoolean(showPlayerLoadingStatusKey)?.let(::saveShowPlayerLoadingStatus)
        payload.decodeSyncBoolean(pauseOverlayEnabledKey)?.let(::savePauseOverlayEnabled)
        payload.decodeSyncBoolean(showParentalGuideKey)?.let(::saveShowParentalGuide)
        payload.decodeSyncString(resizeModeKey)?.let(::saveResizeMode)
        payload.decodeSyncBoolean(holdToSpeedEnabledKey)?.let(::saveHoldToSpeedEnabled)
        payload.decodeSyncFloat(holdToSpeedValueKey)?.let(::saveHoldToSpeedValue)
        payload.decodeSyncBoolean(touchGesturesEnabledKey)?.let(::saveTouchGesturesEnabled)
        payload.decodeSyncBoolean(externalPlayerEnabledKey)?.let(::saveExternalPlayerEnabled)
        payload.decodeSyncBoolean(externalPlayerForwardSubtitlesKey)?.let(::saveExternalPlayerForwardSubtitles)
        payload.decodeSyncBoolean(externalPlayerSendSkipSegmentsKey)?.let(::saveExternalPlayerSendSkipSegments)
        payload.decodeSyncString(externalPlayerIdKey)?.let(::saveExternalPlayerId)
        payload.decodeSyncString(preferredAudioLanguageKey)?.let(::savePreferredAudioLanguage)
        payload.decodeSyncString(secondaryPreferredAudioLanguageKey)?.let(::saveSecondaryPreferredAudioLanguage)
        payload.decodeSyncString(preferredSubtitleLanguageKey)?.let(::savePreferredSubtitleLanguage)
        payload.decodeSyncString(secondaryPreferredSubtitleLanguageKey)?.let(::saveSecondaryPreferredSubtitleLanguage)
        payload.decodeSyncString(subtitleTextColorKey)?.let(::saveSubtitleTextColor)
        payload.decodeSyncString(subtitleBackgroundColorKey)?.let(::saveSubtitleBackgroundColor)
        payload.decodeSyncString(subtitleOutlineColorKey)?.let(::saveSubtitleOutlineColor)
        payload.decodeSyncBoolean(subtitleOutlineEnabledKey)?.let(::saveSubtitleOutlineEnabled)
        payload.decodeSyncInt(subtitleOutlineWidthKey)?.let(::saveSubtitleOutlineWidth)
        payload.decodeSyncBoolean(subtitleBoldKey)?.let(::saveSubtitleBold)
        payload.decodeSyncInt(subtitleFontSizeSpKey)?.let(::saveSubtitleFontSizeSp)
        payload.decodeSyncInt(subtitleBottomOffsetKey)?.let(::saveSubtitleBottomOffset)
        payload.decodeSyncBoolean(subtitleStripSdhKey)?.let(::saveSubtitleStripSdh)
        payload.decodeSyncBoolean(subtitleUseForcedSubtitlesKey)?.let(::saveSubtitleUseForcedSubtitles)
        payload.decodeSyncBoolean(subtitleShowOnlyPreferredLanguagesKey)?.let(::saveSubtitleShowOnlyPreferredLanguages)
        payload.decodeSyncBoolean(streamReuseLastLinkEnabledKey)?.let(::saveStreamReuseLastLinkEnabled)
        payload.decodeSyncInt(streamReuseLastLinkCacheHoursKey)?.let(::saveStreamReuseLastLinkCacheHours)
        payload.decodeSyncString(androidPlaybackEngineKey)?.let(::saveAndroidPlaybackEngine)
        payload.decodeSyncString(androidLibmpvVideoOutputKey)?.let(::saveAndroidLibmpvVideoOutput)
        payload.decodeSyncBoolean(androidLibmpvHardwareDecodingEnabledKey)
            ?.let(::saveAndroidLibmpvHardwareDecodingEnabled)
        payload.decodeSyncBoolean(androidLibmpvYuv420pEnabledKey)?.let(::saveAndroidLibmpvYuv420pEnabled)
        payload.decodeSyncInt(decoderPriorityKey)?.let(::saveDecoderPriority)
        payload.decodeSyncBoolean(mapDV7ToHevcKey)?.let(::saveMapDV7ToHevc)
        payload.decodeSyncBoolean(tunnelingEnabledKey)?.let(::saveTunnelingEnabled)
        payload.decodeSyncString(streamAutoPlayModeKey)?.let(::saveStreamAutoPlayMode)
        payload.decodeSyncString(streamAutoPlaySourceKey)?.let(::saveStreamAutoPlaySource)
        payload.decodeSyncStringSet(streamAutoPlaySelectedAddonsKey)?.let(::saveStreamAutoPlaySelectedAddons)
        payload.decodeSyncStringSet(streamAutoPlaySelectedPluginsKey)?.let(::saveStreamAutoPlaySelectedPlugins)
        payload.decodeSyncString(streamAutoPlayRegexKey)?.let(::saveStreamAutoPlayRegex)
        payload.decodeSyncInt(streamAutoPlayTimeoutSecondsKey)?.let(::saveStreamAutoPlayTimeoutSeconds)
        payload.decodeSyncBoolean(skipIntroEnabledKey)?.let(::saveSkipIntroEnabled)
        payload.decodeSyncBoolean(autoSkipMovieCreditsKey)?.let(::saveAutoSkipMovieCredits)
        payload.decodeSyncStringSet(autoSkipSegmentTypesKey)?.let(::saveAutoSkipSegmentTypes)
        payload.decodeSyncBoolean(autoSkipPostCreditsKey)?.let(::saveAutoSkipPostCredits)
        payload.decodeSyncBoolean(animeSkipEnabledKey)?.let(::saveAnimeSkipEnabled)
        payload.decodeSyncString(animeSkipClientIdKey)?.let(::saveAnimeSkipClientId)
        payload.decodeSyncString(introDbApiKeyKey)?.let(::saveIntroDbApiKey)
        payload.decodeSyncBoolean(introSubmitEnabledKey)?.let(::saveIntroSubmitEnabled)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeEnabled)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeFallbackEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeFallbackEnabled)
        payload.decodeSyncBoolean(streamAutoPlayPreferBingeGroupKey)?.let(::saveStreamAutoPlayPreferBingeGroup)
        payload.decodeSyncBoolean(streamAutoPlayReuseBingeGroupKey)?.let(::saveStreamAutoPlayReuseBingeGroup)
        payload.decodeSyncString(nextEpisodeThresholdModeKey)?.let(::saveNextEpisodeThresholdMode)
        payload.decodeSyncFloat(nextEpisodeThresholdPercentKey)?.let(::saveNextEpisodeThresholdPercent)
        payload.decodeSyncFloat(nextEpisodeThresholdMinutesBeforeEndKey)?.let(::saveNextEpisodeThresholdMinutesBeforeEnd)
        payload.decodeSyncBoolean(preloadNextEpisodeSourcesKey)?.let(::savePreloadNextEpisodeSources)
        payload.decodeSyncBoolean(useLibassKey)?.let(::saveUseLibass)
        payload.decodeSyncString(libassRenderTypeKey)?.let(::saveLibassRenderType)
        payload.decodeSyncString(iosVideoOutputPresetKey)?.let(::saveIosVideoOutputPreset)
        payload.decodeSyncString(iosToneMappingModeKey)?.let(::saveIosToneMappingMode)
        payload.decodeSyncString(iosTargetPrimariesKey)?.let(::saveIosTargetPrimaries)
        payload.decodeSyncString(iosTargetTransferKey)?.let(::saveIosTargetTransfer)
        payload.decodeSyncString(iosHardwareDecoderModeKey)?.let(::saveIosHardwareDecoderMode)
        payload.decodeSyncString(iosAudioOutputModeKey)?.let(::saveIosAudioOutputMode)
        payload.decodeSyncBoolean(iosExtendedDynamicRangeEnabledKey)?.let(::saveIosExtendedDynamicRangeEnabled)
        payload.decodeSyncBoolean(iosTargetColorspaceHintEnabledKey)?.let(::saveIosTargetColorspaceHintEnabled)
        payload.decodeSyncBoolean(iosHdrComputePeakEnabledKey)?.let(::saveIosHdrComputePeakEnabled)
        payload.decodeSyncBoolean(iosDebandEnabledKey)?.let(::saveIosDebandEnabled)
        payload.decodeSyncBoolean(iosInterpolationEnabledKey)?.let(::saveIosInterpolationEnabled)
        payload.decodeSyncInt(iosBrightnessKey)?.let(::saveIosBrightness)
        payload.decodeSyncInt(iosContrastKey)?.let(::saveIosContrast)
        payload.decodeSyncInt(iosSaturationKey)?.let(::saveIosSaturation)
        payload.decodeSyncInt(iosGammaKey)?.let(::saveIosGamma)
    }
}
