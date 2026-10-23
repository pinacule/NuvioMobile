package com.nuvio.app.features.tracking

/**
 * The progress percentage at which a tracking provider counts a view as
 * watched: a STOP scrobble at or above it becomes a history entry, below it
 * the view stays a resumable pause.
 *
 * This is the single definition for that provider-side semantics. Simkl uses
 * it for its scrobble outcome (including the 409 duplicate-stop mapping) and
 * for projecting fetched playback rows into local progress entries, so an
 * item the provider counts as watched is completed in the Simkl projection
 * too and does not sit in continue watching forever while Simkl is the
 * active source.
 *
 * It deliberately differs from the local completion rule
 * (`CompletionThresholdFraction` / `WatchProgressCompletionPercentThreshold`,
 * 90%): that rule decides when the app itself treats a locally recorded view
 * as finished, and local-only behavior (offline use, the Nuvio source) keeps
 * relying on the stricter bar. The two thresholds answer different questions
 * — "did the provider record a watch?" vs "did we finish watching this?" —
 * and are not interchangeable.
 */
const val TrackingWatchedThresholdPercent = 80.0
