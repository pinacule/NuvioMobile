package com.nuvio.app.features.simkl

/**
 * A fetched playback list with the rows the app recorded itself kept when they are newer.
 *
 * Simkl answers a stop or a pause right away, but it publishes the new position on `/sync/playback`
 * a moment later. The read that follows the user's own write can therefore still describe the
 * previous viewing of the same episode, and taking it sends the next resume back to a position the
 * user has already passed: this is how an episode stopped at 83 % resumed at 88 %.
 *
 * The same lag can also leave a just-paused row out of the fetch entirely — most visibly the
 * first viewing of an episode, which has no previous row for the fetch to carry. A held row
 * missing from the fetch is therefore kept when it was paused after [lastSyncedAtEpochMs] (the
 * snapshot's last successful sync: it cannot have been published and dropped since), and dropped
 * when it is older than that. For those older rows the account stays authoritative: a playback
 * it dropped is a playback that is over.
 */
internal fun mergeFetchedPlayback(
    fetched: List<SimklPlaybackSession>,
    held: List<SimklPlaybackSession>,
    lastSyncedAtEpochMs: Long? = null,
): List<SimklPlaybackSession> {
    if (held.isEmpty()) return fetched
    val fetchedByKey = fetched.mapNotNull { session ->
        session.playbackKey()?.let { key -> key to session }
    }.toMap()
    val kept = held.filter { local ->
        val key = local.playbackKey() ?: return@filter false
        val incoming = fetchedByKey[key]
        if (incoming != null) {
            (local.playedAtEpochMs() ?: 0L) > (incoming.playedAtEpochMs() ?: 0L)
        } else {
            val lastSyncedAt = lastSyncedAtEpochMs ?: return@filter false
            (local.playedAtEpochMs() ?: 0L) > lastSyncedAt
        }
    }
    if (kept.isEmpty()) return fetched
    val keptKeys = kept.mapNotNullTo(mutableSetOf()) { session -> session.playbackKey() }
    return fetched.filterNot { session -> session.playbackKey() in keptKeys } + kept
}

/**
 * What a playback row describes: the show or movie, plus the episode when there is one. Two rows of
 * the same show are two playbacks, so the episode has to be part of the identity.
 */
internal fun SimklPlaybackSession.playbackKey(): String? = simklPlaybackKey(media, episode)

/**
 * The [playbackKey] for a playback described only by its media and episode, for callers that hold
 * a scrobble target rather than a session row. A movie has no episode, so both coordinates fall
 * back to -1 and its key is the media alone, exactly as for a movie session row.
 */
internal fun simklPlaybackKey(
    media: SimklMedia?,
    episode: SimklPlaybackEpisode?,
): String? {
    val resolvedMedia = media ?: return null
    val contentId = resolvedMedia.ids.simklIdValue()
        ?: resolvedMedia.ids.idValue("imdb")
        ?: resolvedMedia.ids.idValue("tvdb")
        ?: resolvedMedia.title
        ?: return null
    val season = episode?.tvdbSeason ?: episode?.season ?: -1
    val number = episode?.tvdbNumber ?: episode?.number ?: -1
    return "$contentId|$season|$number"
}

/** When the account says this playback was last touched. */
private fun SimklPlaybackSession.playedAtEpochMs(): Long? =
    (pausedAt ?: watchedAt)?.let(::parseSimklUtcEpochMs)
