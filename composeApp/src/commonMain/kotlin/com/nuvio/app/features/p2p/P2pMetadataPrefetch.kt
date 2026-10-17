package com.nuvio.app.features.p2p

/**
 * How many of the top-listed torrent streams get their metadata prefetched while the stream
 * list is open. Bounded: each entry costs one metadata round-trip against the swarm.
 */
internal const val P2P_METADATA_PREFETCH_MAX_STREAMS = 6

/**
 * A listed torrent stream, reduced to what metadata prefetch needs. Carries the same inputs the
 * playback path feeds into the engine host's `acquireTorrent` (see `P2pStreamRequest`), so a
 * prefetched torrent is exactly the torrent playback would add — no separate magnet-building
 * logic to drift.
 */
internal data class P2pMetadataPrefetchTarget(
    val infoHash: String,
    val trackers: List<String>,
)

/**
 * Opaque handle for a torrent metadata prefetch run.
 *
 * A prefetch run adds the target torrents' *metadata* to the P2P engine (where supported) so a
 * later playback start skips the metadata round-trip. It is metadata-only by design: adding a
 * torrent to the engine does not start any content download — that only begins when a stream is
 * explicitly prepared for playback — and the worst case for a dead torrent is bounded by the
 * per-torrent timeout, with fetched metadata itself bounded by the engine's disk cache.
 */
internal expect class P2pMetadataPrefetchHandle()

/**
 * Best-effort prefetch of torrent metadata for [targets]. Returns a handle that must be released
 * with [releaseP2pMetadataPrefetch], or null where prefetch is unsupported (desktop, iOS) or the
 * engine could not be started.
 *
 * Design notes:
 * - Metadata-only. Each target goes through the same `acquireTorrent` call playback uses, which
 *   suspends until the native metadata-ready event. Content download is gated behind the
 *   separate `prepareStream` step, which prefetch never invokes, so no content bytes are fetched
 *   by design.
 * - Bounded waits. `addMagnet`'s internal metadata wait has no timeout — a dead torrent would
 *   hang forever — so every fetch is wrapped in `withTimeout`, and a timeout is treated as a
 *   quiet skip. At most a few fetches run concurrently.
 * - `preloadStream` is deliberately not used: it performs a loopback HTTP preload of an
 *   already-prepared stream, and prefetch never prepares a stream, so there is no call site.
 * - Torrents the engine already knows are skipped: playback reuses them with no fetch at all.
 * - The engine is held under a weak `warmup:` owner, so a prefetch run never blocks playback's
 *   engine reconfigure nor the torrent-cache exclusive check. If playback reconfigures the
 *   engine mid-prefetch, the in-flight fetch fails its engine-identity check and is quietly
 *   skipped.
 */
internal expect suspend fun acquireP2pMetadataPrefetch(
    targets: List<P2pMetadataPrefetchTarget>,
): P2pMetadataPrefetchHandle?

/**
 * Releases a prefetch run previously returned by [acquireP2pMetadataPrefetch]. Every torrent
 * lease is returned with `removeWhenUnused = true` — a torrent is removed from the engine only
 * when no other download or playback stream still holds a reference, so a racing playback lease
 * is never yanked — and the weak engine owner is dropped. Cancellation-safe and idempotent.
 */
internal expect suspend fun releaseP2pMetadataPrefetch(handle: P2pMetadataPrefetchHandle)
