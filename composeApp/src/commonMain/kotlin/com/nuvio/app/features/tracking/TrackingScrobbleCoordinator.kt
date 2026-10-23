package com.nuvio.app.features.tracking

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable,
)

object TrackingScrobbleCoordinator {
    private val log = Logger.withTag("TrackingScrobble")
    private val outboxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serializes scrobble dispatches in call order.
     *
     * Callers fire scrobbles from independent launches (the player runtime
     * emits the finished episode's terminal STOP/PAUSE and the next episode's
     * START from separate coroutines), so without a shared ordering point the
     * two could reach a provider out of order — a START processed before the
     * previous item's STOP can leave the provider's "now playing" and the
     * local reconciliation pointing at different items. Holding this mutex
     * for the whole dispatch makes the provider-visible order match the
     * order the calls were issued in: in the autoplay-advance path the
     * runtime issues the terminal scrobble first, so it is fully dispatched
     * before the next START begins. (Seek restarts use [scrobbleSeek] and
     * are intentionally not serialized with playback scrobbles.)
     */
    private val scrobbleMutex = Mutex()

    private var flushTriggersInstalled = false

    /**
     * Installs the standing [TrackingScrobbleOutbox] flush triggers
     * (idempotent; safe to call from app startup and lazily from
     * [scrobble]):
     * - one flush immediately, replaying anything a previous session queued;
     * - a flush whenever connectivity is regained, i.e. whenever
     * [NetworkStatusRepository] transitions to Online.
     *
     * Manual sync drains the outbox separately (SyncManager.pullAllForProfile),
     * and a partially successful scrobble drains it in [scrobble]. Every
     * trigger funnels into the same mutex-serialized, profile- and
     * auth-guarded [TrackingScrobbleOutbox.flush], so duplicate triggers
     * are harmless.
     */
    fun ensureFlushTriggers() {
        if (flushTriggersInstalled) return
        flushTriggersInstalled = true
        outboxScope.launch { TrackingScrobbleOutbox.flush() }
        outboxScope.launch {
            NetworkStatusRepository.uiState
                .map { state -> state.isOnline }
                .distinctUntilChanged()
                .collect { isOnline ->
                    if (isOnline) TrackingScrobbleOutbox.flush()
                }
        }
    }

    suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): List<TrackingScrobbleFailure> = scrobbleMutex.withLock {
        if (profileId != ProfileRepository.activeProfileId) {
            return@withLock emptyList()
        }
        ensureFlushTriggers()
        TrackingProviderRegistry.ensureLoaded()
        val scrobblers = TrackingProviderRegistry.connectedScrobblers()
        val failures = dispatchTrackingScrobble(
            scrobblers = scrobblers,
            profileId = profileId,
            action = action,
            event = event,
        )
        for (failure in failures) {
            log.w(failure.cause) {
                "${failure.providerId.storageId} scrobble ${action.wireValue} failed"
            }
            val scrobbler = scrobblers.firstOrNull { it.providerId == failure.providerId }
            if (scrobbler?.isConnectivityFailure(failure.cause) == true) {
                // Device is offline (or the network path failed): persist the
                // event instead of dropping it. It flushes on reconnect.
                TrackingScrobbleOutbox.enqueue(
                    providerId = failure.providerId,
                    profileId = profileId,
                    action = action,
                    event = event,
                )
            }
        }
        if (scrobblers.isNotEmpty() && failures.size < scrobblers.size) {
            // At least one provider was reached, so connectivity is available:
            // drain anything the outbox queued while offline.
            outboxScope.launch { TrackingScrobbleOutbox.flush() }
        }
        failures
    }

    suspend fun scrobbleSeek(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): List<TrackingScrobbleFailure> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
        TrackingProviderRegistry.ensureLoaded()
        val scrobblers = TrackingProviderRegistry.connectedScrobblers()
        val seekScrobblers = scrobblers.filter { scrobbler ->
            scrobbler.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART
        }
        val failures = dispatchTrackingSeekScrobble(
            scrobblers = scrobblers,
            profileId = profileId,
            action = action,
            event = event,
        )
        for (failure in failures) {
            log.w(failure.cause) {
                "${failure.providerId.storageId} seek scrobble ${action.wireValue} failed"
            }
            val scrobbler = scrobblers.firstOrNull { it.providerId == failure.providerId }
            if (scrobbler?.isConnectivityFailure(failure.cause) == true) {
                // Queue seek failures exactly like regular scrobble
                // failures: a seek made offline (or against a transiently
                // failing provider) must still reach the STOP_AND_RESTART
                // provider afterwards, or its playback session / resume
                // position is never corrected.
                TrackingScrobbleOutbox.enqueue(
                    providerId = failure.providerId,
                    profileId = profileId,
                    action = action,
                    event = event,
                )
            }
        }
        if (seekScrobblers.isNotEmpty() && failures.size < seekScrobblers.size) {
            // At least one seek provider was reached, so connectivity is
            // available: drain anything the outbox queued while offline.
            outboxScope.launch { TrackingScrobbleOutbox.flush() }
        }
        return failures
    }
}

internal suspend fun dispatchTrackingSeekScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    profileId: Int,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(
    scrobblers = scrobblers.filter { scrobbler ->
        scrobbler.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART
    },
    profileId = profileId,
    action = action,
    event = event,
)

internal suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    profileId: Int,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers.map { scrobbler ->
        async {
            try {
                scrobbler.scrobble(profileId = profileId, action = action, event = event)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TrackingScrobbleFailure(providerId = scrobbler.providerId, cause = error)
            }
        }
    }.awaitAll().filterNotNull()
}
