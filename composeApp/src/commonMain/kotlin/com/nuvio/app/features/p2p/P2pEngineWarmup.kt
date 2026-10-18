package com.nuvio.app.features.p2p

import kotlinx.coroutines.flow.StateFlow

/**
 * Opaque handle for a speculative P2P engine warmup lease.
 *
 * A warmup lease starts the P2P engine (where supported) without adding any torrent, purely so a
 * subsequent playback start finds a hot engine. Warmup leases are weak: they keep an already
 * running engine alive but must never block playback's engine reconfigure nor the torrent-cache
 * exclusive check.
 */
internal expect class P2pEngineWarmupLease()

/**
 * Current owners of the shared P2P engine (e.g. `download:<id>`, `warmup:streams`). Lets
 * speculative work (warmup, metadata prefetch) yield while real downloads hold the engine.
 * Empty on platforms without a shared in-process engine.
 */
internal expect val p2pEngineOwners: StateFlow<Set<String>>

/**
 * Best-effort speculative start of the P2P engine. Returns a lease that must be released with
 * [releaseP2pEngineWarmup], or null where warmup is unsupported (desktop, iOS) or the engine
 * could not be started.
 *
 * No torrent is added and nothing is downloaded; the engine idles out on its own once the last
 * lease — warmup or otherwise — is released.
 */
internal expect suspend fun acquireP2pEngineWarmup(): P2pEngineWarmupLease?

/** Releases a lease previously returned by [acquireP2pEngineWarmup]. */
internal expect suspend fun releaseP2pEngineWarmup(lease: P2pEngineWarmupLease)
