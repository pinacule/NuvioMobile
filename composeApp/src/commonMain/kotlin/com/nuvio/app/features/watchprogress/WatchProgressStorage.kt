package com.nuvio.app.features.watchprogress

internal expect object WatchProgressStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)

    /** The last-good copy of the payload, written by [saveBackupPayload] for decode recovery. */
    fun loadBackupPayload(profileId: Int): String?
    fun saveBackupPayload(profileId: Int, payload: String)
}
