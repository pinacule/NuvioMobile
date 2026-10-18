package com.nuvio.app.features.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal actual class P2pEngineWarmupLease actual constructor()

/** No shared lease host on iOS, so there are never any owners. */
internal actual val p2pEngineOwners: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/**
 * Warmup is unsupported on iOS: the AppStore engine is a stub, and the Full build drives its
 * native engine directly without a shared lease host.
 */
internal actual suspend fun acquireP2pEngineWarmup(): P2pEngineWarmupLease? = null

internal actual suspend fun releaseP2pEngineWarmup(lease: P2pEngineWarmupLease) {
    // No-op: acquire never returns a lease on iOS.
}
