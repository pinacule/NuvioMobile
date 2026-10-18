package com.nuvio.app.features.tracking

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline outbox for tracking scrobbles.
 *
 * The tracking pipeline is fire-and-forget: when a scrobble fails because the
 * device has no connectivity, the event would otherwise be lost forever (none
 * of the providers retry). The coordinator hands such failures to
 * [enqueue]; [flush] replays them once connectivity is available again.
 *
 * Coalescing: only the latest event per (profile, provider, media) is kept —
 * a later pause supersedes earlier pauses/starts for the same episode, and a
 * stop supersedes everything before it.
 *
 * Flush mapping per provider:
 * - progress >= 80% and a history writer is registered: one batched
 *   [TrackingHistoryWriter.addToHistory] call carrying the ORIGINAL
 *   [QueuedScrobble.queuedAtEpochMs] as `watched_at`, so the item counts as
 *   watched on the provider;
 * - otherwise: the original scrobble action is replayed through the
 *   provider's [TrackingScrobbler] (a pause below 80% restores the resume
 *   point).
 *
 * Events whose provider is no longer authenticated are left queued; the
 * 200-record cap (oldest dropped) bounds the storage.
 */
object TrackingScrobbleOutbox {
    private const val MAX_QUEUED_RECORDS = 200
    private const val WATCHED_THRESHOLD_PERCENT = 80.0

    private val log = Logger.withTag("TrackingScrobbleOutbox")
    private val json = Json { ignoreUnknownKeys = true }

    /** Guards read-modify-write cycles on the stored payload. */
    private val storageMutex = Mutex()

    /** Serializes flush runs so two concurrent flushes cannot double-send. */
    private val flushMutex = Mutex()

    suspend fun enqueue(
        providerId: TrackingProviderId,
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ) {
        val record = QueuedScrobble(
            providerId = providerId.storageId,
            profileId = profileId,
            action = action.wireValue,
            media = event.media,
            progressPercent = event.progressPercent,
            queuedAtEpochMs = TrackingScrobbleOutboxClock.nowEpochMs(),
        )
        storageMutex.withLock {
            val records = loadRecords().toMutableList()
            // Latest event per (profile, provider, media) wins.
            records.removeAll { it.coalesceKey == record.coalesceKey }
            records.add(record)
            while (records.size > MAX_QUEUED_RECORDS) {
                records.removeAt(0)
            }
            try {
                saveRecords(records)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Never let outbox persistence break playback tracking.
                log.e(error) { "Failed to persist queued scrobble for ${providerId.storageId}" }
            }
        }
        log.d { "Queued ${action.wireValue} scrobble for ${providerId.storageId} (${record.media.stableKey})" }
    }

    /**
     * Replays queued events. Safe to call any time: it is a no-op when the
     * queue is empty, keeps events whose provider is unauthenticated, and
     * stops at the first connectivity failure (the device is offline again).
     */
    suspend fun flush() {
        flushMutex.withLock {
            TrackingProviderRegistry.ensureLoaded()
            val activeProfileId = ProfileRepository.activeProfileId
            // Only deliver for the active profile: provider auth and request
            // scoping are active-profile based, so replaying another profile's
            // events here would attribute them to the wrong account.
            val records = storageMutex.withLock { loadRecords() }
                .filter { it.profileId == activeProfileId }
            if (records.isEmpty()) return
            log.d { "Flushing ${records.size} queued scrobbles" }
            records.groupBy { it.providerId to it.profileId }
                .forEach { (key, group) -> flushGroup(key.first, key.second, group) }
        }
    }

    private suspend fun flushGroup(
        providerStorageId: String,
        profileId: Int,
        group: List<QueuedScrobble>,
    ) {
        val providerId = TrackingProviderId.fromStorage(providerStorageId)
        if (providerId == null) {
            log.w { "Dropping ${group.size} queued scrobbles for unknown provider $providerStorageId" }
            removeRecords(group)
            return
        }
        if (!TrackingProviderRegistry.isAuthenticated(providerId)) {
            // Provider disconnected; leave queued (bounded by the cap).
            return
        }
        val scrobbler = TrackingProviderRegistry.scrobbler(providerId)
        val historyWriter = TrackingProviderRegistry.historyWriter(providerId)
        val ordered = group.sortedBy { it.queuedAtEpochMs }
        val (watched, inProgress) = ordered.partition { it.progressPercent >= WATCHED_THRESHOLD_PERCENT }

        if (watched.isNotEmpty() && historyWriter != null) {
            val error = runFlushCall {
                historyWriter.addToHistory(
                    profileId,
                    watched.map { TrackingHistoryItem(media = it.media, watchedAtEpochMs = it.queuedAtEpochMs) },
                )
            }
            if (error == null) {
                log.d { "Flushed ${watched.size} watched items to $providerStorageId history" }
                removeRecords(watched)
            } else if (isConnectivityFailure(scrobbler, error)) {
                return
            } else {
                log.w(error) { "Dropping ${watched.size} unflushable history items for $providerStorageId" }
                removeRecords(watched)
            }
        }

        // Without a history writer (or for <80% progress) replay the original
        // scrobble action through the provider: a pause restores the resume point.
        val replay = if (historyWriter != null) inProgress else ordered
        for (record in replay) {
            if (scrobbler == null) {
                log.w { "No scrobbler for $providerStorageId; keeping ${replay.size} queued" }
                return
            }
            val action = TrackingScrobbleAction.entries.firstOrNull { it.wireValue == record.action }
                ?: TrackingScrobbleAction.PAUSE
            val error = runFlushCall {
                scrobbler.scrobble(
                    profileId,
                    action,
                    TrackingScrobbleEvent(media = record.media, progressPercent = record.progressPercent),
                )
            }
            if (error == null) {
                removeRecords(listOf(record))
            } else if (isConnectivityFailure(scrobbler, error)) {
                return
            } else {
                log.w(error) { "Dropping unflushable queued scrobble for $providerStorageId" }
                removeRecords(listOf(record))
            }
        }
    }

    private fun isConnectivityFailure(scrobbler: TrackingScrobbler?, error: Throwable): Boolean =
        scrobbler?.isConnectivityFailure(error) ?: error.hasConnectivityCause()

    /** Runs [block], returning the thrown error or null. Cancellation always propagates. */
    private suspend fun runFlushCall(block: suspend () -> Unit): Throwable? {
        return try {
            block()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
    }

    private suspend fun removeRecords(records: List<QueuedScrobble>) {
        if (records.isEmpty()) return
        val identities = records.map { it.identity }.toSet()
        storageMutex.withLock {
            saveRecords(loadRecords().filterNot { it.identity in identities })
        }
    }

    private fun loadRecords(): List<QueuedScrobble> {
        val payload = TrackingScrobbleOutboxStorage.loadPayload()
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(payload)
        } catch (error: Exception) {
            log.w(error) { "Dropping corrupt scrobble outbox payload" }
            emptyList()
        }
    }

    private fun saveRecords(records: List<QueuedScrobble>) {
        TrackingScrobbleOutboxStorage.savePayload(json.encodeToString(records))
    }

    /**
     * Removes all queued records for [profileId] from a raw payload. Used by
     * the platform storage actuals; the JSON schema lives here in common code.
     */
    internal fun filterPayloadForProfile(payload: String?, profileId: Int): String {
        if (payload.isNullOrBlank()) return ""
        return try {
            json.encodeToString(json.decodeFromString<List<QueuedScrobble>>(payload).filterNot { it.profileId == profileId })
        } catch (error: Exception) {
            log.w(error) { "Dropping corrupt scrobble outbox payload during profile removal" }
            ""
        }
    }
}

/**
 * One scrobble event that could not be delivered.
 *
 * @property action the original [TrackingScrobbleAction.wireValue], replayed
 * on flush when the provider has no history writer.
 * @property queuedAtEpochMs enqueue time; reused as the original `watched_at`
 * when the record flushes through a history writer.
 */
@Serializable
internal data class QueuedScrobble(
    val providerId: String,
    val profileId: Int,
    val action: String,
    val media: TrackingMediaReference,
    val progressPercent: Double,
    val queuedAtEpochMs: Long,
) {
    /** Key for coalescing: latest event per (profile, provider, media) wins. */
    val coalesceKey: String
        get() = "$profileId|$providerId|${media.stableKey}"

    /** Stable identity used to remove exactly the records a flush delivered. */
    val identity: String
        get() = "$coalesceKey|$queuedAtEpochMs"
}
