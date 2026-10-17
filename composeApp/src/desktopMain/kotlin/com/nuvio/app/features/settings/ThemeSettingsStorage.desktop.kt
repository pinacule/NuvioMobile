package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object ThemeSettingsStorage {
    private const val selectedThemeKey = "selected_theme"
    private const val customThemeColorsKey = "custom_theme_colors"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
    private const val selectedAppLanguageKey = "selected_app_language"
    private const val NAV_BAR_STYLE_KEY = "nav_bar_style"
    private const val navBarGlowEnabledKey = "nav_bar_glow_enabled"
    private val profileScopedSyncKeys = listOf(
        selectedThemeKey,
        customThemeColorsKey,
        amoledEnabledKey,
        navBarGlowEnabledKey,
        liquidGlassNativeTabBarEnabledKey,
        NAV_BAR_STYLE_KEY,
    )

    private val prefs = DesktopPreferences("theme_settings")

    actual fun loadSelectedTheme(): String? =
        prefs.getString(ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) {
        prefs.putString(ProfileScopedKey.of(selectedThemeKey), themeName)
    }

    actual fun loadCustomThemeColors(): String? =
        prefs.getString(ProfileScopedKey.of(customThemeColorsKey))

    actual fun saveCustomThemeColors(colors: String) {
        prefs.putString(ProfileScopedKey.of(customThemeColorsKey), colors)
    }

    actual fun loadAmoledEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(amoledEnabledKey), enabled)
    }

    actual fun loadNavBarGlowEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(navBarGlowEnabledKey))

    actual fun saveNavBarGlowEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(navBarGlowEnabledKey), enabled)
    }

    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? =
        prefs.getBoolean(ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey))

    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
        prefs.putBoolean(ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey), enabled)
    }

    actual fun loadSelectedAppLanguage(): String? {
        val value = prefs.getString(selectedAppLanguageKey)
        if (value != null) return value
        val legacy = prefs.getString(ProfileScopedKey.of(selectedAppLanguageKey))
        if (legacy != null) saveSelectedAppLanguage(legacy)
        return legacy
    }

    actual fun saveSelectedAppLanguage(languageCode: String) {
        prefs.putString(selectedAppLanguageKey, languageCode)
    }

    actual fun applySelectedAppLanguage(languageCode: String) {
        // Desktop uses the system locale; a language change applies on next launch.
    }

    actual fun loadNavBarStyle(): String? =
        prefs.getString(ProfileScopedKey.of(NAV_BAR_STYLE_KEY))

    actual fun saveNavBarStyle(styleKey: String) {
        prefs.putString(ProfileScopedKey.of(NAV_BAR_STYLE_KEY), styleKey)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadCustomThemeColors()?.let { put(customThemeColorsKey, encodeSyncString(it)) }
        loadNavBarGlowEnabled()?.let { put(navBarGlowEnabledKey, encodeSyncBoolean(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadLiquidGlassNativeTabBarEnabled()?.let { put(liquidGlassNativeTabBarEnabledKey, encodeSyncBoolean(it)) }
        loadNavBarStyle()?.let { put(NAV_BAR_STYLE_KEY, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        profileScopedSyncKeys.forEach { prefs.remove(ProfileScopedKey.of(it)) }

        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncString(customThemeColorsKey)?.let(::saveCustomThemeColors)
        payload.decodeSyncBoolean(navBarGlowEnabledKey)?.let(::saveNavBarGlowEnabled)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncBoolean(liquidGlassNativeTabBarEnabledKey)?.let(::saveLiquidGlassNativeTabBarEnabled)
        payload.decodeSyncString(NAV_BAR_STYLE_KEY)?.let(::saveNavBarStyle)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.DEVICE.code)
    }
}
