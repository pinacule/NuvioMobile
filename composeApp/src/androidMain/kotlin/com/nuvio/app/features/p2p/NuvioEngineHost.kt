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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val HOST_TAG = "NuvioEngineHost"

/**
 * Owns the single Nuvio Engine instance shared by playback and torrent downloads.
 *
 * Every user of the engine holds a lease under an owner key (`playback`, `download:<id>`). The engine
 * is only reconfigured when nobody else holds a lease, and it shuts down once the last lease is
 * released. Torrents are reference counted the same way, so a torrent is never removed while another
 * download or playback stream still uses it.
 */
internal object NuvioEngineHost {
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
    private val stateLock = Any()

    @Volatile
    private var appContext: Context? = null
    private var engine: NuvioEngine? = null
    private var configurationKey: ConfigurationKey? = null
    private var engineEventsJob: Job? = null
    private val engineOwners = mutableSetOf<String>()
    private val knownTorrents = mutableMapOf<String, String>()
    private val torrentLeases = mutableMapOf<String, MutableSet<TorrentLease>>()

    private val _owners = MutableStateFlow<Set<String>>(emptySet())
    val owners: StateFlow<Set<String>> = _owners.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun hasOwnersOtherThan(owner: String): Boolean =
        synchronized(stateLock) { (engineOwners - owner).isNotEmpty() }

    fun isTorrentKnown(infoHash: String): Boolean =
        synchronized(stateLock) { canonicalP2pInfoHash(infoHash) in knownTorrents }

    /**
     * Returns the shared engine and records [owner] as a user of it. When the P2P settings changed
     * since the engine was created, it is recreated only if no other owner is using it; otherwise the
     * running engine is reused and the new settings apply once it is idle.
     */
    suspend fun acquireEngine(owner: String): NuvioEngine = engineMutex.withLock {
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val desiredKey = ConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )
        val current = engine
        if (current != null && (configurationKey == desiredKey || hasOwnersOtherThan(owner))) {
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

    /** Drops [owner]'s lease and shuts the engine down when nobody uses it anymore. */
    suspend fun releaseEngine(owner: String) {
        withContext(NonCancellable) {
            engineMutex.withLock {
                val idleEngine = synchronized(stateLock) {
                    if (!engineOwners.remove(owner)) return@withLock
                    _owners.value = engineOwners.toSet()
                    engine.takeIf { engineOwners.isEmpty() }
                }
                idleEngine?.let { closeEngineLocked(it) }
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
        engineOwners += owner
        _owners.value = engineOwners.toSet()
    }

    private fun createEngine(key: ConfigurationKey): NuvioEngine {
        val context = appContext ?: throw P2pStreamingException("P2P streaming engine is not initialized")
        val stateDirectory = File(context.noBackupFilesDir, "nuvio-engine/state")
        val cacheDirectory = File(context.cacheDir, "nuvio-engine")
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
            Log.i(HOST_TAG, "Using Nuvio Engine ${NuvioEngine.version} (${NuvioEngine.protocolBackendVersion})")
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

internal val P2P_DEFAULT_TRACKERS = listOf(
    "udp://zer0day.ch:1337/announce",
    "udp://tracker.publictracker.xyz:6969/announce",
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "http://tracker.renfei.net:8080/announce",
    "udp://udp.tracker.projectk.org:23333/announce",
    "udp://tracker.tryhackx.org:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.theoks.net:6969/announce",
    "udp://tracker.startwork.cv:1337/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://tracker.plx.im:6969/announce",
    "udp://tracker.nyaa.vc:6969/announce",
    "udp://tracker.iperson.xyz:6969/announce",
    "udp://tracker.gmi.gd:6969/announce",
    "udp://tracker.fnix.net:6969/announce",
    "udp://tracker.flatuslifir.is:6969/announce",
    "udp://tracker.ducks.party:1984/announce",
    "udp://tracker.bluefrog.pw:2710/announce",
)
