package com.nuvio.app.features.p2p

import android.util.Log
import com.nuvio.engine.NuvioEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val PREFETCH_TAG = "P2pMetadataPrefetch"

/**
 * Engine owner for metadata prefetch. Uses the weak `warmup:` prefix: prefetch holds torrent
 * leases but no stream, so like the stream-list warmup lease it must never block playback's
 * engine reconfigure nor the torrent-cache exclusive check.
 */
private const val PREFETCH_OWNER = "warmup:prefetch"

/**
 * Per-torrent metadata fetch deadline. `NuvioEngine.addMagnet` suspends until the native
 * metadata-ready event and has no internal timeout, so a dead torrent would hang the prefetch
 * forever without this bound.
 */
private const val PREFETCH_TIMEOUT_MS = 15_000L

/** At most this many metadata fetches in flight at once; keeps speculation cheap on mobile data. */
private const val PREFETCH_MAX_PARALLEL = 2

internal actual class P2pMetadataPrefetchHandle actual constructor() {
    internal val released = AtomicBoolean(false)

    /**
     * Torrent leases held by this run. Only mutated while the acquiring coroutine is still
     * running; once the handle is returned the list is stable and the releaser owns it.
     */
    internal val leases = mutableListOf<NuvioEngineHost.TorrentLease>()
}

internal actual suspend fun acquireP2pMetadataPrefetch(
    targets: List<P2pMetadataPrefetchTarget>,
): P2pMetadataPrefetchHandle? {
    if (targets.isEmpty()) return null
    // Own weak engine lease so the engine cannot idle out mid-prefetch even if the stream-list
    // warmup lease is released first. If this throws, the host did not record the owner, so
    // there is nothing to balance below.
    val engine = try {
        NuvioEngineHost.acquireEngine(PREFETCH_OWNER)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(PREFETCH_TAG, "Could not start the engine for metadata prefetch", error)
        return null
    }
    // Leases are collected here as children finish, so a cancelled or failed run can still
    // release everything it fetched instead of leaking torrent references. Structured
    // concurrency guarantees every child has completed before the catch blocks below run, so
    // the synchronized handoff cannot race with teardown.
    val acquiredLock = Any()
    val acquired = mutableListOf<NuvioEngineHost.TorrentLease>()
    try {
        supervisorScope {
            val semaphore = Semaphore(PREFETCH_MAX_PARALLEL)
            targets.map { target ->
                async {
                    semaphore.withPermit {
                        prefetchOne(engine, target)?.let { lease ->
                            synchronized(acquiredLock) { acquired += lease }
                        }
                    }
                }
            }.awaitAll()
        }
        return P2pMetadataPrefetchHandle().also { handle ->
            synchronized(acquiredLock) { handle.leases.addAll(acquired) }
        }
    } catch (cancellation: CancellationException) {
        releaseAcquired(acquiredLock, acquired)
        throw cancellation
    } catch (error: Exception) {
        releaseAcquired(acquiredLock, acquired)
        Log.w(PREFETCH_TAG, "Metadata prefetch run failed", error)
        return null
    }
}

internal actual suspend fun releaseP2pMetadataPrefetch(handle: P2pMetadataPrefetchHandle) {
    if (!handle.released.compareAndSet(false, true)) return
    // Cancellation-safe: a cancelled releaser must still return the torrent leases, otherwise
    // prefetched torrents would pin the engine's torrent table until the engine shuts down.
    withContext(NonCancellable) {
        handle.leases.forEach { lease ->
            // Refcounted: the torrent is removed from the engine only when no other download
            // or playback stream still holds a reference, so a racing playback lease is never
            // yanked by a prefetch release.
            NuvioEngineHost.releaseTorrent(lease, removeWhenUnused = true)
        }
        // Balance the engine owner taken in acquireP2pMetadataPrefetch. Weak owner: the
        // engine idles out on its own afterwards per the usual keep-alive delay.
        NuvioEngineHost.releaseEngine(PREFETCH_OWNER)
    }
}

/** Releases a partial run's leases and balances the engine owner. Already NonCancellable-safe. */
private suspend fun releaseAcquired(
    acquiredLock: Any,
    acquired: MutableList<NuvioEngineHost.TorrentLease>,
) {
    val toRelease = synchronized(acquiredLock) { acquired.toList() }
    withContext(NonCancellable) {
        toRelease.forEach { NuvioEngineHost.releaseTorrent(it, removeWhenUnused = true) }
        NuvioEngineHost.releaseEngine(PREFETCH_OWNER)
    }
}

/**
 * Fetches metadata for a single torrent: adds the magnet (metadata-only — content download is
 * gated behind the separate `prepareStream` step, which prefetch never calls) and touches the
 * file list to confirm usable metadata arrived. Returns the held lease, or null when the
 * torrent is already known, unreachable, or the engine went away underneath us.
 *
 * The returned lease is always either handed to the caller or released before propagating a
 * failure; it is never dropped on the floor.
 */
private suspend fun prefetchOne(
    engine: NuvioEngine,
    target: P2pMetadataPrefetchTarget,
): NuvioEngineHost.TorrentLease? {
    // Playback reuses known torrents with no fetch at all; re-adding would be a wasted round-trip.
    if (NuvioEngineHost.isTorrentKnown(target.infoHash)) return null
    val lease = try {
        withTimeout(PREFETCH_TIMEOUT_MS) {
            // Same inputs as the playback path: acquireTorrent builds the magnet from the
            // infohash plus the default and per-stream trackers, so the prefetched torrent is
            // exactly what playback would add.
            NuvioEngineHost.acquireTorrent(
                activeEngine = engine,
                infoHash = target.infoHash,
                trackers = target.trackers,
            )
        }
    } catch (timeout: TimeoutCancellationException) {
        // Our own deadline, not an external cancellation: the torrent is dead or unreachable.
        // Best-effort — playback still works, it just pays the metadata round-trip itself.
        //
        // Cancellation bookkeeping: if the native add ignored the cancellation and delivered
        // the torrent anyway, acquireTorrent notices the cancelled caller and hands the fresh
        // lease straight back (see NuvioEngineHost.acquireTorrent), so no registered-but-unheld
        // lease is left behind. Two residuals cannot be closed from Kotlin, both inside the
        // closed-source engine bridge: (1) if the bridge honours cancellation by throwing after
        // the native side already added the torrent, the torrent sits in the engine untracked —
        // metadata only, no content download — until a later acquire of the same hash adopts it
        // through the engine's own dedup, or the engine restarts; (2) if the native add never
        // returns and ignores cancellation, it keeps the host's torrent mutex, and no
        // Kotlin-side timeout can preempt a non-cooperative native call.
        Log.i(PREFETCH_TAG, "Metadata prefetch timed out for ${target.infoHash.take(12)}")
        return null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (restarted: IllegalStateException) {
        // "The torrent engine was restarted": playback reconfigured the engine mid-prefetch
        // (weak leases never block that). The fetch targeted the old engine; quiet skip.
        return null
    } catch (error: Exception) {
        Log.i(PREFETCH_TAG, "Metadata prefetch skipped for ${target.infoHash.take(12)}: ${error.message}")
        return null
    }
    try {
        engine.files(lease.torrentId)
    } catch (cancellation: CancellationException) {
        // The caller only tracks leases prefetchOne returns: release it before propagating.
        NuvioEngineHost.releaseTorrent(lease, removeWhenUnused = true)
        throw cancellation
    } catch (error: Exception) {
        // Metadata itself arrived (addMagnet completed); a file-list read failure must not
        // discard that — playback still reuses the torrent with no re-fetch.
        Log.w(PREFETCH_TAG, "Could not read prefetched file list for ${target.infoHash.take(12)}", error)
    }
    return lease
}
