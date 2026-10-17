package com.nuvio.app.features.p2p

internal actual class P2pMetadataPrefetchHandle actual constructor()

/**
 * Metadata prefetch is unsupported on desktop: P2P runs through TorrServer (policy-disabled)
 * rather than a warmable in-process engine.
 */
internal actual suspend fun acquireP2pMetadataPrefetch(
    targets: List<P2pMetadataPrefetchTarget>,
): P2pMetadataPrefetchHandle? = null

internal actual suspend fun releaseP2pMetadataPrefetch(handle: P2pMetadataPrefetchHandle) {
    // No-op: acquire never returns a handle on desktop.
}
