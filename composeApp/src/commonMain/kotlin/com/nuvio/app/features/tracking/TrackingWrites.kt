package com.nuvio.app.features.tracking

import kotlin.io.IOException

enum class TrackingListStatus(val wireValue: String) {
    WATCHING("watching"),
    PLAN_TO_WATCH("plantowatch"),
    ON_HOLD("hold"),
    COMPLETED("completed"),
    DROPPED("dropped");

    companion object {
        internal fun fromWireValue(value: String?): TrackingListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

enum class TrackingScrobbleAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop"),
}

enum class TrackingSeekScrobblePolicy {
    NONE,
    STOP_AND_RESTART,
}

data class TrackingHistoryItem(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long? = null,
)

data class TrackingScrobbleEvent(
    val media: TrackingMediaReference,
    val progressPercent: Double,
)

data class TrackingMutationResult(
    val attemptedCount: Int,
    val notFoundCount: Int = 0,
    val resolutions: List<TrackingMutationResolution> = emptyList(),
) {
    val resolvedListStatuses: List<TrackingListStatus>
        get() = resolutions.mapNotNull(TrackingMutationResolution::listStatus)

    val isComplete: Boolean
        get() = notFoundCount == 0
}

data class TrackingMutationResolution(
    val listStatus: TrackingListStatus? = null,
    val mediaKind: TrackingMediaKind? = null,
    val providerSubtype: String? = null,
)

interface TrackingListWriter {
    val providerId: TrackingProviderId

    suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult

    suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult
}

interface TrackingHistoryWriter {
    val providerId: TrackingProviderId

    suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult

    suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult
}

interface TrackingScrobbler {
    val providerId: TrackingProviderId
    val seekScrobblePolicy: TrackingSeekScrobblePolicy
        get() = TrackingSeekScrobblePolicy.NONE

    suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    )

    /**
     * Whether [error] looks like a connectivity failure (offline, DNS, timeout)
     * rather than an application-level failure (auth, validation, rate limit).
     * Connectivity failures are queued in the offline outbox for later retry;
     * anything else is dropped after logging.
     *
     * The default implementation walks the cause chain for [IOException].
     * Providers that wrap transport failures in their own exception types
     * should override this.
     */
    fun isConnectivityFailure(error: Throwable): Boolean = error.hasConnectivityCause()
}

/**
 * Walks the cause chain looking for IO failures (no route to host, DNS,
 * connect/read timeouts). Never treats [CancellationException][kotlinx.coroutines.CancellationException]
 * as connectivity: cancellation must always propagate.
 */
internal fun Throwable.hasConnectivityCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return true
        current = current.cause
    }
    return false
}
