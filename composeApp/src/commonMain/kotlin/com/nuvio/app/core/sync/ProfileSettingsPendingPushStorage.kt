package com.nuvio.app.core.sync

/**
 * Raw string persistence for [ProfileSettingsSync]'s pending-push markers.
 * The payload is the encoded profileId-to-generation map; the sync object
 * owns the encoding (see encode/restorePendingPushGenerations in
 * ProfileSettingsPullDecision.kt).
 *
 * Backed by the same per-platform key-value mechanism as the settings and
 * sync storages: SharedPreferences on Android, DesktopPreferences on
 * desktop, NSUserDefaults on iOS. Unlike the settings payloads themselves
 * this is intentionally NOT profile-scoped via ProfileScopedKey: the map
 * keys already are profile ids, so markers for non-active profiles survive
 * a profile switch exactly like the in-memory map does.
 */
internal expect object ProfileSettingsPendingPushStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun clearPayload()
}
