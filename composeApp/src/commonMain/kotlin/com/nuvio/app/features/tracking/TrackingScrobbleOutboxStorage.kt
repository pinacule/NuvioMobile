package com.nuvio.app.features.tracking

/**
 * Raw string persistence for the scrobble outbox. The payload is a JSON array
 * of [QueuedScrobble]; the outbox owns the encoding. Unlike provider sync
 * payloads this is intentionally NOT profile-scoped: queued events carry
 * their own profileId so they can flush for any profile.
 */
internal expect object TrackingScrobbleOutboxStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /**
     * Preserves a payload that failed to decode, so a corrupt outbox is
     * never silently replaced by an empty queue; the raw copy stays
     * available for diagnosis/recovery.
     */
    fun saveCorruptPayloadBackup(payload: String)
    fun removeProfile(profileId: Int)
}
