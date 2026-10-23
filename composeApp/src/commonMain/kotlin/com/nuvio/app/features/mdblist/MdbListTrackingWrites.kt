package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingSeekScrobblePolicy
import kotlinx.coroutines.CancellationException

class MdbListTrackingWrites(
    private val sync: MdbListSyncRepository,
    private val history: MdbListHistoryService,
    private val scrobble: MdbListScrobbleService,
) : TrackingHistoryWriter, TrackingScrobbler {
    override val providerId = TrackingProviderId.MDBLIST
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>) =
        history.add(scope(profileId), items)

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>) =
        history.remove(scope(profileId), items)

    override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) =
        scrobble.scrobble(scope(profileId), action, event)

    override fun isConnectivityFailure(error: Throwable): Boolean =
        super.isConnectivityFailure(error) || isMdbListQueueableFailure(error)

    private fun scope(profileId: Int): MdbListAuthScope = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("MDBList profile changed")
    }
}

/**
 * Whether an MDBList failure is transient enough that the scrobble
 * coordinator / outbox should persist and replay it instead of dropping
 * it — mirrors `isSimklQueueableFailure`. MDBList's client reports
 * exhausted transport retries as code "transport_failure" and rate
 * limiting as HTTP 429 / code "rate_limited"; those, plus 408 and 5xx,
 * are queueable. Other 4xx are permanent (auth, validation, not found)
 * and must not be queued. Before this classification existed, a
 * rate-limited or server-errored MDBList scrobble surfaced as a plain
 * failure the coordinator dropped — and during outbox replay the record
 * was removed as if sent.
 */
internal fun isMdbListQueueableFailure(error: Throwable): Boolean {
    if (error !is MdbListApiException) return false
    if (error.code == "transport_failure" || error.code == "rate_limited") return true
    val status = error.status ?: return false
    return status == 408 || status == 429 || status in 500..599
}
