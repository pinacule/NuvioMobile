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
 * Coalescing: only the latest event per (profile, provider, media, episode)
 * is kept — a later pause supersedes earlier pauses/starts for the same
 * episode, and a stop supersedes everything before it for that episode.
 * Different episodes of the same show never coalesce: [TrackingMediaReference.stableKey]
 * identifies the show/movie only, so the episode is part of the key.
 *
 * Flush mapping per provider (mirrors the live path in
 * `SimklScrobbleResult`: only a STOP can become a watched mark):
 * - a STOP at progress >= 80% and a history writer is registered: one batched
 *   [TrackingHistoryWriter.addToHistory] call carrying the ORIGINAL
 *   [QueuedScrobble.queuedAtEpochMs] as `watched_at`, so the item counts as
 *   watched on the provider;
 * - otherwise: the original scrobble action is replayed through the
 *   provider's [TrackingScrobbler] — a PAUSE stays a pause at ANY progress
 *   (it restores the resume point instead of being upgraded to a watched
 *   mark), and a STOP below 80% replays as a stop the provider itself maps
 *   to a pause.
 *
 * Delivery accounting: a history flush only removes records when the
 * provider's [TrackingMutationResult] proves delivery (it attempted the
 * items and reports none not-found). A no-op result (attemptedCount == 0,
 * e.g. the profile switched mid-flush) or a partial result keeps the batch
 * queued; the result type carries no per-item accounting, so a partial
 * batch is retried as a whole. Failed flush attempts are counted per
 * record and a record is dropped — logged at warn — only after
 * [MAX_FLUSH_ATTEMPTS] failed attempts.
 *
 * Events whose provider is no longer authenticated are left queued; the
 * 200-record cap (oldest dropped, logged) bounds the storage.
 */
object TrackingScrobbleOutbox {
    private const val MAX_QUEUED_RECORDS = 200

    /** Failed flush attempts after which a record is dropped (and logged). */
    private const val MAX_FLUSH_ATTEMPTS = 5

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
            // Latest event per (profile, provider, media, episode) wins.
            val records = coalesceQueuedScrobbles(loadRecords(), record).toMutableList()
            var evictedCount = 0
            while (records.size > MAX_QUEUED_RECORDS) {
                records.removeAt(0)
                evictedCount += 1
            }
            if (evictedCount > 0) {
                log.w {
                    "Scrobble outbox is full ($MAX_QUEUED_RECORDS records); " +
                        "evicted $evictedCount oldest queued scrobble(s)"
                }
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
        // Only a STOP at/above the watched threshold becomes history; every
        // other record (a PAUSE at any progress, a START, a STOP below the
        // threshold) replays its original action, like the live path.
        val (watched, replayable) = splitQueuedScrobblesForFlush(ordered)

        if (watched.isNotEmpty() && historyWriter != null) {
            val outcome = runHistoryFlushCall {
                historyWriter.addToHistory(
                    profileId,
                    watched.map { TrackingHistoryItem(media = it.media, watchedAtEpochMs = it.queuedAtEpochMs) },
                )
            }
            when (outcome) {
                is HistoryFlushCallOutcome.Completed -> {
                    when (historyFlushDecision(outcome.result)) {
                        HistoryFlushDecision.REMOVE -> {
                            log.d { "Flushed ${watched.size} watched items to $providerStorageId history" }
                            if (!removeRecordsIfProfileActive(profileId, watched)) return
                        }
                        HistoryFlushDecision.KEEP_UNATTEMPTED -> {
                            // The writer no-oped (attemptedCount == 0), e.g.
                            // the profile switched mid-flush: nothing was
                            // delivered, so this is not an attempt — keep the
                            // records queued untouched.
                            log.d {
                                "History flush for $providerStorageId attempted no items; " +
                                    "keeping ${watched.size} queued"
                            }
                        }
                        HistoryFlushDecision.KEEP_PARTIAL -> {
                            // The provider could not match some items. The
                            // result has no per-item accounting, so the batch
                            // stays queued as a whole (delivered items are
                            // re-sent idempotently); the attempt counter
                            // bounds how long an unmatchable batch can linger.
                            log.w {
                                "History flush for $providerStorageId matched only part of the batch " +
                                    "(notFound=${outcome.result.notFoundCount} of " +
                                    "${outcome.result.attemptedCount}); keeping ${watched.size} queued"
                            }
                            recordFlushFailures(watched, cause = null)
                        }
                    }
                }
                is HistoryFlushCallOutcome.Failed -> {
                    if (isConnectivityFailure(scrobbler, outcome.error)) return
                    recordFlushFailures(watched, cause = outcome.error)
                }
            }
        }

        // Without a history writer (or for records that are not finished
        // STOPs) replay the original scrobble action through the provider:
        // a pause restores the resume point.
        val replay = if (historyWriter != null) replayable else ordered
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
                if (!removeRecordsIfProfileActive(profileId, listOf(record))) return
            } else if (isConnectivityFailure(scrobbler, error)) {
                return
            } else {
                recordFlushFailures(listOf(record), cause = error)
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

    /** Outcome of one batched history flush: the provider's result, or the error it threw. */
    private sealed interface HistoryFlushCallOutcome {
        data class Completed(val result: TrackingMutationResult) : HistoryFlushCallOutcome
        data class Failed(val error: Throwable) : HistoryFlushCallOutcome
    }

    /** Runs a history flush, capturing its [TrackingMutationResult]. Cancellation always propagates. */
    private suspend fun runHistoryFlushCall(
        block: suspend () -> TrackingMutationResult,
    ): HistoryFlushCallOutcome {
        return try {
            HistoryFlushCallOutcome.Completed(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            HistoryFlushCallOutcome.Failed(error)
        }
    }

    /**
     * Counts a failed flush attempt against [failed] records: each record's
     * attempt count is incremented and persisted, and records that reach
     * [MAX_FLUSH_ATTEMPTS] are dropped — every drop is logged at warn with
     * its cause. Connectivity failures never reach this function: they stop
     * the flush without counting as an attempt.
     */
    private suspend fun recordFlushFailures(failed: Collection<QueuedScrobble>, cause: Throwable?) {
        if (failed.isEmpty()) return
        val failedIdentities = failed.map { it.identity }.toSet()
        val dropped = storageMutex.withLock {
            val current = loadRecords()
            val application = applyFlushFailures(current, failedIdentities, MAX_FLUSH_ATTEMPTS)
            if (application.records != current) {
                saveRecords(application.records)
            }
            application.dropped
        }
        dropped.forEach { record ->
            if (cause != null) {
                log.w(cause) {
                    "Dropping queued scrobble for ${record.providerId} after $MAX_FLUSH_ATTEMPTS " +
                        "failed flush attempts (${record.media.stableKey})"
                }
            } else {
                log.w {
                    "Dropping queued scrobble for ${record.providerId} after $MAX_FLUSH_ATTEMPTS " +
                        "failed flush attempts (${record.media.stableKey})"
                }
            }
        }
    }

    /**
     * Removes [records] only if [profileId] is still the active profile.
     * Provider calls made during a flush can silently no-op (or, for
     * providers without an internal guard, silently "succeed") when the
     * user switches profiles mid-flush; removing the records then would
     * erase events that were never delivered to any account. Returns false
     * when the records were kept because the profile changed.
     */
    private suspend fun removeRecordsIfProfileActive(
        profileId: Int,
        records: List<QueuedScrobble>,
    ): Boolean {
        if (ProfileRepository.activeProfileId != profileId) {
            log.w {
                "Active profile changed during outbox flush; keeping ${records.size} " +
                    "queued scrobbles for profile $profileId"
            }
            return false
        }
        removeRecords(records)
        return true
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
            // Fail safe: a corrupt payload must not silently become an
            // empty queue that the next save makes permanent. Preserve the
            // raw payload in the backup slot before starting from empty.
            log.w(error) {
                "Scrobble outbox payload is corrupt; preserving a backup copy and starting with an empty queue"
            }
            runCatching { TrackingScrobbleOutboxStorage.saveCorruptPayloadBackup(payload) }
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
            // Same fail-safe as loadRecords: never let a corrupt payload
            // vanish without a trace — keep the raw copy in the backup slot.
            log.w(error) {
                "Scrobble outbox payload is corrupt during profile removal; preserving a backup copy"
            }
            runCatching { TrackingScrobbleOutboxStorage.saveCorruptPayloadBackup(payload) }
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
 * @property attemptCount failed flush attempts so far; the record is
 * dropped (logged) once this reaches the outbox's attempt cap.
 * Connectivity failures do not count.
 */
@Serializable
internal data class QueuedScrobble(
    val providerId: String,
    val profileId: Int,
    val action: String,
    val media: TrackingMediaReference,
    val progressPercent: Double,
    val queuedAtEpochMs: Long,
    val attemptCount: Int = 0,
) {
    /**
     * Key for coalescing: latest event per (profile, provider, media, episode)
     * wins. [TrackingMediaReference.stableKey] deliberately carries no episode
     * coordinates (Simkl history building groups a show's episodes by it), so
     * the episode is appended here instead of changing that key: episodes are
     * `s<season>e<number>`, and a reference without an episode (a movie, or a
     * whole-show event) is `movie`.
     */
    val coalesceKey: String
        get() = "$profileId|$providerId|${media.stableKey}|" +
            (media.episode?.let { episode -> "s${episode.season}e${episode.number}" } ?: "movie")

    /** Stable identity used to remove exactly the records a flush delivered. */
    val identity: String
        get() = "$coalesceKey|$queuedAtEpochMs"
}

/**
 * The enqueue coalescing rule: any already-queued record with the same
 * [QueuedScrobble.coalesceKey] as [record] is dropped, then [record] is
 * appended (so the newest event for an episode is also the last one).
 * Extracted so the rule can be unit-tested without platform storage.
 */
internal fun coalesceQueuedScrobbles(
    records: List<QueuedScrobble>,
    record: QueuedScrobble,
): List<QueuedScrobble> =
    records.filterNot { it.coalesceKey == record.coalesceKey } + record

/** Progress at/above which a STOP counts as watched (matches the live path). */
private const val WATCHED_THRESHOLD_PERCENT = 80.0

/**
 * The flush split: (history-bound, replay-bound). Only a STOP at/above
 * [WATCHED_THRESHOLD_PERCENT] flushes through the history writer — the same
 * rule the live path applies (`SimklScrobbleResult` maps only STOP >= 80%
 * to a watched outcome). A PAUSE keeps its action at any progress, so a
 * queued pause at 85% restores the resume point instead of being upgraded
 * to a watched mark. Extracted for unit testing.
 */
internal fun splitQueuedScrobblesForFlush(
    ordered: List<QueuedScrobble>,
): Pair<List<QueuedScrobble>, List<QueuedScrobble>> =
    ordered.partition { record ->
        record.action == TrackingScrobbleAction.STOP.wireValue &&
            record.progressPercent >= WATCHED_THRESHOLD_PERCENT
    }

/** What a completed history flush means for the flushed records. */
internal enum class HistoryFlushDecision {
    /** The provider attempted the items and none were not-found: delivered. */
    REMOVE,

    /** The provider attempted nothing (attemptedCount == 0): not an attempt. */
    KEEP_UNATTEMPTED,

    /** Some items were not found; the batch cannot be partially resolved. */
    KEEP_PARTIAL,
}

/**
 * Interprets a [TrackingMutationResult] from a batched history flush.
 * Extracted for unit testing.
 */
internal fun historyFlushDecision(result: TrackingMutationResult): HistoryFlushDecision = when {
    result.attemptedCount == 0 -> HistoryFlushDecision.KEEP_UNATTEMPTED
    result.isComplete -> HistoryFlushDecision.REMOVE
    else -> HistoryFlushDecision.KEEP_PARTIAL
}

/** Result of counting one failed flush attempt against the stored records. */
internal data class FlushFailureApplication(
    /** The full record list after incrementing (dropped records removed). */
    val records: List<QueuedScrobble>,
    /** Records whose attempt count reached the cap and were dropped. */
    val dropped: List<QueuedScrobble>,
)

/**
 * Increments [QueuedScrobble.attemptCount] for every record whose identity
 * is in [failedIdentities]; records reaching [maxAttempts] are dropped.
 * Records not in [failedIdentities] pass through untouched. Extracted for
 * unit testing.
 */
internal fun applyFlushFailures(
    records: List<QueuedScrobble>,
    failedIdentities: Set<String>,
    maxAttempts: Int,
): FlushFailureApplication {
    val dropped = mutableListOf<QueuedScrobble>()
    val updated = records.mapNotNull { record ->
        if (record.identity !in failedIdentities) return@mapNotNull record
        val incremented = record.copy(attemptCount = record.attemptCount + 1)
        if (incremented.attemptCount >= maxAttempts) {
            dropped += incremented
            null
        } else {
            incremented
        }
    }
    return FlushFailureApplication(records = updated, dropped = dropped)
}
