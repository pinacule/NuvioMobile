package com.nuvio.app.features.p2p

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal actual class P2pEngineWarmupLease actual constructor() {
    internal val released = AtomicBoolean(false)
}

/** Live view of [NuvioEngineHost] owners; torrent downloads hold `download:<id>` owners. */
internal actual val p2pEngineOwners: StateFlow<Set<String>> = NuvioEngineHost.owners

/**
 * Serializes warmup lease bookkeeping. [NuvioEngineHost] tracks owners as a set, so concurrent
 * warmup leases share the single `warmup:streams` owner entry; the counter below makes
 * acquire/release pair up correctly no matter how many leases are live at once.
 */
private val warmupLeaseLock = Mutex()
private var warmupLeaseCount = 0

internal actual suspend fun acquireP2pEngineWarmup(): P2pEngineWarmupLease? =
    warmupLeaseLock.withLock {
        if (warmupLeaseCount == 0) {
            try {
                // Takes a normal host lease under the canonical warmup owner. No torrent is
                // added: the engine simply starts (or stays) running. If this throws, the host
                // did not record the owner, so there is nothing to balance below.
                NuvioEngineHost.acquireEngine(NuvioEngineHost.WARMUP_OWNER)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@withLock null
            }
        }
        warmupLeaseCount++
        P2pEngineWarmupLease()
    }

internal actual suspend fun releaseP2pEngineWarmup(lease: P2pEngineWarmupLease) {
    if (!lease.released.compareAndSet(false, true)) return
    // Cancellation-safe: a cancelled releaser must still balance the counter, otherwise the
    // host owner would leak and the engine would never idle out.
    withContext(NonCancellable) {
        val dropHostLease = warmupLeaseLock.withLock {
            warmupLeaseCount = (warmupLeaseCount - 1).coerceAtLeast(0)
            warmupLeaseCount == 0
        }
        // Last warmup lease out: drop the host owner. The engine then idles out on its own after
        // the usual keep-alive delay unless playback or a download still holds a lease.
        if (dropHostLease) {
            NuvioEngineHost.releaseEngine(NuvioEngineHost.WARMUP_OWNER)
        }
    }
}
