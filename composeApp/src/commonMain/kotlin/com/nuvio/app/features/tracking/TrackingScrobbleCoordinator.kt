package com.nuvio.app.features.tracking

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable,
)

object TrackingScrobbleCoordinator {
    private val log = Logger.withTag("TrackingScrobble")
    private val outboxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): List<TrackingScrobbleFailure> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
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
        return failures
    }

    suspend fun scrobbleSeek(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): List<TrackingScrobbleFailure> {
        if (profileId != ProfileRepository.activeProfileId) return emptyList()
        TrackingProviderRegistry.ensureLoaded()
        val failures = dispatchTrackingSeekScrobble(
            scrobblers = TrackingProviderRegistry.connectedScrobblers(),
            profileId = profileId,
            action = action,
            event = event,
        )
        failures.forEach { failure ->
            log.w(failure.cause) {
                "${failure.providerId.storageId} seek scrobble ${action.wireValue} failed"
            }
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
