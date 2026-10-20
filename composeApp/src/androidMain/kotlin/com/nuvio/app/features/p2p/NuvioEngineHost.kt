package com.nuvio.app.features.p2p

import android.content.Context
import android.util.Log
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEventType
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val HOST_TAG = "NuvioEngineHost"

/** Keeps an unused engine (and its peers) alive briefly so retries and quick resumes reuse the swarm. */
private const val IDLE_SHUTDOWN_DELAY_MS = 60_000L

/**
 * Bound on waiting for the stream-preparation lock. A preparation that never
 * returns must not wedge every later download, preload or playback forever:
 * the waiter gives up and the normal retry/backoff path takes over.
 */
private const val PREPARE_STREAM_LOCK_TIMEOUT_MS = 60_000L

/**
 * Owns the single Nuvio Engine instance shared by playback and torrent downloads.
 *
 * Every user of the engine holds a lease under an owner key (`playback`, `download:<id>`,
 * `warmup:streams`). The engine is only reconfigured when nobody else holds a lease, and it shuts
 * down once the last lease is released. Torrents are reference counted the same way, so a torrent
 * is never removed while another download or playback stream still uses it.
 *
 * Warmup leases (owner keys starting with `warmup:`) are weak: they keep an already-running
 * engine alive but never block reconfiguration for real work and never block the cache-clear
 * exclusive check. See [hasNonWarmupOwnersOtherThan].
 *
 * Engine tuning that lives below the Kotlin API — piece prioritization, sequential readahead,
 * peer/connection limits — is configured inside the closed-source native engine
 * (lib-nuvio-engine-android, libtorrent-based) and cannot be changed from Kotlin.
 * `NuvioEngine.preloadStream` exists in the native API but performs a loopback HTTP
 * preload of an already-added stream, so it has no safe call site on the warmup path, which by
 * design never adds a torrent; it is therefore intentionally unused here.
 */
internal object NuvioEngineHost {
    /**
     * Owner key for speculative engine warmup (see `P2pEngineWarmup`). The engine is started
     * without adding any torrent; the warmup lease only keeps it alive.
     */
    const val WARMUP_OWNER = "warmup:streams"

    /** Prefix identifying weak warmup leases. Any current or future warmup owner must use it. */
    const val WARMUP_OWNER_PREFIX = "warmup:"

    private data class ConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
    )

    class TorrentLease internal constructor(
        internal val engine: NuvioEngine,
        val infoHash: String,
        val torrentId: String,
    ) {
        internal val released = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val torrentMutex = Mutex()
    /**
     * Serializes stream preparation across the whole app. The native engine
     * rejects a second concurrent preparation with "stream preparation already
     * pending", and torrent downloads (max 2 concurrent slots), the silent
     * preload and playback can all ask for one at the same time. Waiters are
     * served FIFO; the wait is bounded by [PREPARE_STREAM_LOCK_TIMEOUT_MS] so
     * one stuck preparation cannot wedge the queue forever.
     */
    private val prepareStreamMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var appContext: Context? = null
    private var engine: NuvioEngine? = null
    private var configurationKey: ConfigurationKey? = null
    private var engineEventsJob: Job? = null
    private var idleShutdownJob: Job? = null
    private val engineOwners = EngineOwnerRefCounts()
    /**
     * In-memory dedup of magnets added to the *current* engine, keyed by canonical infohash.
     * This is intentionally not persisted: it is only valid for the running engine instance.
     * Torrent metadata itself is persisted on disk by the native engine inside its state directory
     * (`nuvio-engine/state`, see [createEngine]), so when a magnet is re-added after an engine or
     * process restart the native side can restore the metadata it already fetched instead of
     * refetching it from the swarm. Re-adding is still required — the native engine starts with
     * no torrents after a shutdown — but the metadata fetch step is skipped for known infohashes.
     */
    private val knownTorrents = mutableMapOf<String, String>()
    private val torrentLeases = mutableMapOf<String, MutableSet<TorrentLease>>()

    private val _owners = MutableStateFlow<Set<String>>(emptySet())
    val owners: StateFlow<Set<String>> = _owners.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun hasOwnersOtherThan(owner: String): Boolean =
        synchronized(stateLock) { engineOwners.snapshot().any { it != owner } }

    /**
     * Like [hasOwnersOtherThan] but ignores warmup leases. A warmup lease must never block
     * playback's engine reconfigure nor the torrent-cache exclusive check: it holds no torrent
     * and no stream, it only keeps an already-running engine from idling out.
     */
    fun hasNonWarmupOwnersOtherThan(owner: String): Boolean =
        synchronized(stateLock) {
            engineOwners.snapshot().any { it != owner && !it.startsWith(WARMUP_OWNER_PREFIX) }
        }

    fun isTorrentKnown(infoHash: String): Boolean =
        synchronized(stateLock) { canonicalP2pInfoHash(infoHash) in knownTorrents }

    /**
     * Returns the shared engine and records [owner] as a user of it. When the P2P settings changed
     * since the engine was created, it is recreated only if no other non-warmup owner is using it;
     * a lone warmup lease never blocks the reconfigure (it holds no torrent or stream, so there is
     * nothing to preserve). Otherwise the running engine is reused and the new settings apply once
     * it is idle.
     */
    suspend fun acquireEngine(owner: String): NuvioEngine = engineMutex.withLock {
        idleShutdownJob?.cancel()
        idleShutdownJob = null
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val desiredKey = ConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )
        val current = engine
        if (current != null && (configurationKey == desiredKey || hasNonWarmupOwnersOtherThan(owner))) {
            addOwner(owner)
            return@withLock current
        }
        if (current != null) {
            Log.i(HOST_TAG, "Recreating Nuvio Engine for configuration $desiredKey")
            closeEngineLocked(current)
        }
        currentCoroutineContext().ensureActive()
        createEngine(desiredKey).also { created ->
            synchronized(stateLock) {
                engine = created
                configurationKey = desiredKey
            }
            observeEngineEvents(created)
            addOwner(owner)
        }
    }

    /**
     * Drops [owner]'s lease. Once nobody uses the engine it shuts down after [IDLE_SHUTDOWN_DELAY_MS],
     * unless someone acquires it again first.
     */
    suspend fun releaseEngine(owner: String) {
        withContext(NonCancellable) {
            engineMutex.withLock {
                val idleEngine = synchronized(stateLock) {
                    // Not the owner's last hold (or it held none): the lease
                    // stays up, so there is nothing to idle out.
                    if (!engineOwners.release(owner)) return@withLock
                    _owners.value = engineOwners.snapshot()
                    engine.takeIf { engineOwners.isEmpty() }
                } ?: return@withLock
                idleShutdownJob?.cancel()
                idleShutdownJob = scope.launch {
                    delay(IDLE_SHUTDOWN_DELAY_MS)
                    engineMutex.withLock {
                        val stillIdle = synchronized(stateLock) {
                            engine === idleEngine && engineOwners.isEmpty()
                        }
                        if (stillIdle) closeEngineLocked(idleEngine)
                    }
                }
            }
        }
    }

    /**
     * Adds the torrent to [activeEngine] (or reuses it when already added) and takes a reference on
     * it. Every lease must be returned through [releaseTorrent].
     */
    suspend fun acquireTorrent(
        activeEngine: NuvioEngine,
        infoHash: String,
        trackers: List<String>,
    ): TorrentLease = torrentMutex.withLock {
        val canonicalHash = canonicalP2pInfoHash(infoHash)
        val knownTorrentId = synchronized(stateLock) {
            check(engine === activeEngine) { "The torrent engine was restarted" }
            knownTorrents[canonicalHash]
        }
        val torrentId = knownTorrentId ?: activeEngine.addMagnet(
            buildP2pMagnetUri(canonicalHash, (P2P_DEFAULT_TRACKERS + trackers).distinct()),
        )
        synchronized(stateLock) {
            check(engine === activeEngine) { "The torrent engine was restarted" }
            knownTorrents[canonicalHash] = torrentId
            TorrentLease(activeEngine, canonicalHash, torrentId).also { lease ->
                torrentLeases.getOrPut(torrentId) { mutableSetOf() } += lease
            }
        }
    }

    /**
     * Returns a torrent reference. The torrent is removed from the engine only when [removeWhenUnused]
     * is set and no other download or playback stream still holds a reference to it.
     */
    suspend fun releaseTorrent(lease: TorrentLease, removeWhenUnused: Boolean) {
        if (!lease.released.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            torrentMutex.withLock {
                val shouldRemove = synchronized(stateLock) {
                    if (engine !== lease.engine) return@synchronized false
                    val holders = torrentLeases[lease.torrentId] ?: return@synchronized false
                    holders.remove(lease)
                    if (holders.isNotEmpty()) return@synchronized false
                    torrentLeases.remove(lease.torrentId)
                    if (!removeWhenUnused) return@synchronized false
                    knownTorrents.remove(lease.infoHash)
                    true
                }
                if (shouldRemove) {
                    try {
                        lease.engine.removeTorrent(lease.torrentId)
                    } catch (error: Exception) {
                        Log.w(HOST_TAG, "Error removing torrent ${lease.torrentId.take(12)}", error)
                    }
                }
            }
        }
    }

    private fun addOwner(owner: String) = synchronized(stateLock) {
        engineOwners.acquire(owner)
        _owners.value = engineOwners.snapshot()
    }

    /**
     * Runs [block] with the stream-preparation lock held: only one
     * `NuvioEngine.prepareStream` call app-wide at a time (downloads, silent
     * preload and playback all go through here). Throws
     * [kotlinx.coroutines.TimeoutCancellationException] when the wait exceeds
     * [PREPARE_STREAM_LOCK_TIMEOUT_MS]; callers should translate that into a
     * retryable, human-readable failure, never surface it raw.
     */
    suspend fun <T> withPrepareStreamLock(block: suspend () -> T): T =
        withTimeout(PREPARE_STREAM_LOCK_TIMEOUT_MS) {
            prepareStreamMutex.withLock { block() }
        }

    private fun createEngine(key: ConfigurationKey): NuvioEngine {
        val context = appContext ?: throw P2pStreamingException("P2P streaming engine is not initialized")
        val stateDirectory = File(context.noBackupFilesDir, "nuvio-engine/state")
        val cacheDirectory = File(context.cacheDir, "nuvio-engine")
        // The native engine persists torrent metadata under the state directory across restarts.
        // Logging whether it already holds data tells us (in NuvioP2PDiag-adjacent logs) whether a
        // freshly created engine could restore previously fetched metadata instead of refetching.
        val hadPriorState = stateDirectory.isDirectory && (stateDirectory.list()?.isNotEmpty() == true)
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create the Nuvio Engine state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create the Nuvio Engine cache directory"
        }
        migrateNestedPayloadDirectory(cacheDirectory)
        return NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = key.uploadEnabled,
                torrentProfile = key.torrentProfile,
                diskCacheCapacityBytes = key.diskCacheCapacityBytes,
            ),
        ).also {
            Log.i(
                HOST_TAG,
                "Using Nuvio Engine ${NuvioEngine.version} (${NuvioEngine.protocolBackendVersion}) " +
                    "priorState=$hadPriorState",
            )
        }
    }

    private suspend fun closeEngineLocked(target: NuvioEngine) {
        synchronized(stateLock) {
            if (engine === target) {
                engine = null
                configurationKey = null
                knownTorrents.clear()
                torrentLeases.clear()
                engineEventsJob?.cancel()
                engineEventsJob = null
            }
        }
        withContext(NonCancellable) {
            try {
                target.shutdown()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(HOST_TAG, "Error shutting down Nuvio Engine", error)
            }
        }
    }

    private fun observeEngineEvents(activeEngine: NuvioEngine) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            activeEngine.events.collect { event ->
                if (event.type != NuvioEventType.TorrentRemoved) return@collect
                val torrentId = event.torrentId ?: return@collect
                synchronized(stateLock) {
                    if (engine !== activeEngine) return@synchronized
                    knownTorrents.entries.removeAll { it.value == torrentId }
                    torrentLeases.remove(torrentId)
                }
            }
        }
    }
}

/**
 * Reference counts for [NuvioEngineHost] owner leases (R-8).
 *
 * Two holders can lease the engine under the SAME owner key at once — a cancelled
 * download/preload attempt still unwinding while its replacement already runs,
 * overlapping metadata-prefetch runs, back-to-back warmup generations. A plain
 * owner set counts such a key once, so the first holder's release dropped the
 * lease under the second holder. Counting holds per key fixes that: an owner is
 * only gone when its last hold is released.
 *
 * Not thread-safe on its own: [NuvioEngineHost] only touches it while holding
 * its `stateLock`.
 */
internal class EngineOwnerRefCounts {
    private val counts = mutableMapOf<String, Int>()

    /** Records one more hold for [owner]. */
    fun acquire(owner: String) {
        counts[owner] = (counts[owner] ?: 0) + 1
    }

    /**
     * Drops one hold for [owner]. Returns true when that was the owner's last
     * hold (the owner no longer holds a lease); false when the owner held no
     * lease at all, or still holds another one after this release.
     */
    fun release(owner: String): Boolean {
        val count = counts[owner] ?: return false
        return if (count <= 1) {
            counts.remove(owner)
            true
        } else {
            counts[owner] = count - 1
            false
        }
    }

    fun isEmpty(): Boolean = counts.isEmpty()

    /** How many holds [owner] currently has (0 when it holds no lease). */
    fun countOf(owner: String): Int = counts[owner] ?: 0

    /** The owners currently holding at least one lease. */
    fun snapshot(): Set<String> = counts.keys.toSet()
}

/**
 * Default trackers appended to every magnet the app adds to the engine. Best-effort list of
 * long-lived public trackers: the engine tries them in order and moves on from unresponsive ones,
 * so breadth matters more than any single entry. UDP liveness cannot be verified from every
 * network (some sandbox/VPN setups block raw UDP), so entries are kept while their domains
 * resolve and the swarm is expected to route around dead ones via DHT/PEX as well.
 */
internal val P2P_DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.tiny-vps.com:6969/announce",
    "udp://opentracker.i2p.rocks:6969/announce",
    "udp://tracker.eddie4.nl:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://zer0day.ch:1337/announce",
    "udp://tracker.publictracker.xyz:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.theoks.net:6969/announce",
    "udp://tracker.gmi.gd:6969/announce",
    "udp://tracker.fnix.net:6969/announce",
    "udp://tracker.flatuslifir.is:6969/announce",
    "udp://tracker.ducks.party:1984/announce",
    "udp://tracker.bluefrog.pw:2710/announce",
    "udp://tracker.nyaa.vc:6969/announce",
    "udp://tracker.iperson.xyz:6969/announce",
    "udp://tracker.plx.im:6969/announce",
    "udp://tracker.startwork.cv:1337/announce",
    "udp://tracker.tryhackx.org:6969/announce",
    "udp://udp.tracker.projectk.org:23333/announce",
    "http://tracker.renfei.net:8080/announce",
)
