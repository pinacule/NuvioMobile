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
    fun removeProfile(profileId: Int)
}
