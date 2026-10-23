package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /** The last-good copy of the payload, written by [saveBackupPayload] for decode recovery. */
    fun loadBackupPayload(): String?
    fun saveBackupPayload(payload: String)
}
