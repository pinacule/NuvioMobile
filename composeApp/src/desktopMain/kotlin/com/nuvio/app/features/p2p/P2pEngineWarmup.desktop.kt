package com.nuvio.app.features.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal actual class P2pEngineWarmupLease actual constructor()

/** No shared in-process engine on desktop, so there are never any owners. */
internal actual val p2pEngineOwners: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/**
 * Desktop P2P runs through TorrServer (policy-disabled) rather than a warmable in-process
 * engine, so warmup is unsupported here.
 */
internal actual suspend fun acquireP2pEngineWarmup(): P2pEngineWarmupLease? = null

internal actual suspend fun releaseP2pEngineWarmup(lease: P2pEngineWarmupLease) {
    // No-op: acquire never returns a lease on desktop.
}
