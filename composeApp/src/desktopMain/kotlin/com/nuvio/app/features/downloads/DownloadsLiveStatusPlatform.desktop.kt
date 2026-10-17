package com.nuvio.app.features.downloads

/**
 * Desktop stub: no system notification / live-status surface exists on desktop,
 * so download progress is only visible inside the app.
 */
internal actual object DownloadsLiveStatusPlatform {
    actual fun onItemsChanged(items: List<DownloadItem>) = Unit
}
