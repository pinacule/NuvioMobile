package com.nuvio.app.features.search

import com.nuvio.app.core.storage.DesktopPreferences

internal actual object DiscoverSelectionStorage {
    private const val catalogKeyPref = "discover_catalog_key"

    private val prefs = DesktopPreferences("discover_selection")

    actual fun loadCatalogKey(): String? = prefs.getString(catalogKeyPref)

    actual fun saveCatalogKey(catalogKey: String) {
        prefs.putString(catalogKeyPref, catalogKey)
    }
}
