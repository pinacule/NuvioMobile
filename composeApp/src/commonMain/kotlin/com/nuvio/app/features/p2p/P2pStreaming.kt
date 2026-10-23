package com.nuvio.app.features.p2p

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class P2pSettingsUiState(
    val p2pEnabled: Boolean = false,
    val enableUpload: Boolean = true,
    val hideTorrentStats: Boolean = false,
    val torrentProfile: P2pTorrentProfile = DefaultP2pTorrentProfile,
    val cacheSize: P2pCacheSize = P2pCacheSize.GB_2,
)

enum class P2pTorrentProfile {
    SOFT,
    BALANCED,
    FAST,
}

enum class P2pCacheSize(val bytes: Long) {
    NONE(0L),
    GB_2(2L * 1024L * 1024L * 1024L),
    GB_5(5L * 1024L * 1024L * 1024L),
    GB_10(10L * 1024L * 1024L * 1024L),
}

data class P2pCacheUiState(
    val usedBytes: Long = 0L,
    val protectedBytes: Long = 0L,
    val isClearing: Boolean = false,
    val hasMeasurement: Boolean = false,
)

data class P2pCacheClearResult(
    val reclaimedBytes: Long,
    val remainingBytes: Long,
    val protectedBytes: Long,
)

object P2pSettingsRepository {
    private val _uiState = MutableStateFlow(P2pSettingsUiState())
    val uiState: StateFlow<P2pSettingsUiState> = _uiState.asStateFlow()

    val isVisible: Boolean
        get() = AppFeaturePolicy.p2pEnabled

    private var hasLoaded = false
    private var p2pEnabled = false
    private var enableUpload = true
    private var hideTorrentStats = false
    private var torrentProfile = DefaultP2pTorrentProfile
    private var cacheSize = P2pCacheSize.GB_2

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        p2pEnabled = false
        enableUpload = true
        hideTorrentStats = false
        torrentProfile = DefaultP2pTorrentProfile
        cacheSize = P2pCacheSize.GB_2
        publish()
    }

    fun setP2pEnabled(enabled: Boolean) {
        ensureLoaded()
        if (p2pEnabled == enabled) return
        p2pEnabled = enabled
        P2pSettingsStorage.saveP2pEnabled(enabled)
        publish()
    }

    fun setEnableUpload(enabled: Boolean) {
        ensureLoaded()
        if (enableUpload == enabled) return
        enableUpload = enabled
        P2pSettingsStorage.saveEnableUpload(enabled)
        publish()
    }

    fun setHideTorrentStats(enabled: Boolean) {
        ensureLoaded()
        if (hideTorrentStats == enabled) return
        hideTorrentStats = enabled
        P2pSettingsStorage.saveHideTorrentStats(enabled)
        publish()
    }

    fun setTorrentProfile(profile: P2pTorrentProfile) {
        ensureLoaded()
        // Any selection in the settings UI is an explicit choice, even
        // re-selecting the profile that is already active: record it so the
        // load-time migration never overrides it.
        P2pSettingsStorage.saveTorrentProfileExplicitlySet(true)
        if (torrentProfile == profile) return
        torrentProfile = profile
        P2pSettingsStorage.saveTorrentProfile(profile.name)
        publish()
    }

    fun setCacheSize(size: P2pCacheSize) {
        ensureLoaded()
        if (cacheSize == size) return
        cacheSize = size
        P2pSettingsStorage.saveCacheSize(size.name)
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        p2pEnabled = P2pSettingsStorage.loadP2pEnabled() ?: false
        enableUpload = P2pSettingsStorage.loadEnableUpload() ?: true
        hideTorrentStats = P2pSettingsStorage.loadHideTorrentStats() ?: false
        val torrentProfileResolution = resolveTorrentProfile(
            storedProfileName = P2pSettingsStorage.loadTorrentProfile(),
            explicitlySet = P2pSettingsStorage.loadTorrentProfileExplicitlySet() ?: false,
        )
        torrentProfile = torrentProfileResolution.profile
        torrentProfileResolution.profileToPersist?.let { migratedProfile ->
            // One-time migration to the Fast default for installs with no
            // recorded explicit choice. Persisting the value together with
            // the explicit-choice marker is what makes it exactly once: from
            // now on the stored value governs, including later user picks.
            P2pSettingsStorage.saveTorrentProfile(migratedProfile.name)
            P2pSettingsStorage.saveTorrentProfileExplicitlySet(true)
        }
        cacheSize = P2pSettingsStorage.loadCacheSize()
            ?.let { stored -> P2pCacheSize.entries.firstOrNull { it.name == stored } }
            ?: P2pCacheSize.GB_2
        publish()
    }

    private fun publish() {
        _uiState.value = P2pSettingsUiState(
            p2pEnabled = p2pEnabled,
            enableUpload = enableUpload,
            hideTorrentStats = hideTorrentStats,
            torrentProfile = torrentProfile,
            cacheSize = cacheSize,
        )
    }
}

internal expect object P2pSettingsStorage {
    fun loadP2pEnabled(): Boolean?
    fun saveP2pEnabled(enabled: Boolean)
    fun loadEnableUpload(): Boolean?
    fun saveEnableUpload(enabled: Boolean)
    fun loadHideTorrentStats(): Boolean?
    fun saveHideTorrentStats(enabled: Boolean)
    fun loadTorrentProfile(): String?
    fun saveTorrentProfile(profile: String)
    fun loadTorrentProfileExplicitlySet(): Boolean?
    fun saveTorrentProfileExplicitlySet(explicitlySet: Boolean)
    fun loadCacheSize(): String?
    fun saveCacheSize(size: String)
}

data class P2pStreamRequest(
    val infoHash: String,
    val fileIdx: Int?,
    val filename: String? = null,
    val trackers: List<String> = emptyList(),
)

internal fun canonicalP2pInfoHash(infoHash: String): String {
    val trimmed = infoHash.trim()
    val canonical = trimmed.lowercase()
    if ((canonical.length == 40 || canonical.length == 64) &&
        canonical.all { it in '0'..'9' || it in 'a'..'f' }
    ) {
        return canonical
    }
    // BitTorrent v1 hashes also circulate as RFC 4648 base32 (32 characters,
    // no padding) — in magnet btih topics and torrent:// URLs. Stream
    // validation accepts that form, so decode it to the same 20-byte hex
    // representation here, at the canonicalization point every engine,
    // download and prefetch path funnels through; a hex-only check would
    // reject base32 torrents outright (and playback surfaced that raw).
    if (trimmed.length == BASE32_INFO_HASH_LENGTH) {
        decodeBase32InfoHash(trimmed)?.let { return it }
    }
    throw IllegalArgumentException(
        "Torrent info hash must be 40 or 64 hexadecimal characters or a 32-character base32 hash",
    )
}

/**
 * The canonical form of [infoHash] when it parses as a torrent info hash
 * (hex or base32), or null when it does not. For hash comparisons and other
 * addon-supplied values that must not throw.
 */
internal fun canonicalP2pInfoHashOrNull(infoHash: String?): String? =
    infoHash?.let { runCatching { canonicalP2pInfoHash(it) }.getOrNull() }

private const val BASE32_INFO_HASH_LENGTH = 32

/**
 * Decodes an RFC 4648 base32 info hash (32 characters, no padding, either
 * case) to its 40-character lowercase hex form, or null when [value] holds a
 * character outside the base32 alphabet. 32 characters × 5 bits are exactly
 * the 20 bytes of a v1 info hash, so no padding or remainder handling arises.
 */
private fun decodeBase32InfoHash(value: String): String? {
    val bytes = ByteArray(20)
    var buffer = 0
    var bitsInBuffer = 0
    var byteIndex = 0
    for (char in value) {
        val symbol = when (char) {
            in 'A'..'Z' -> char - 'A'
            in 'a'..'z' -> char - 'a'
            in '2'..'7' -> char - '2' + 26
            else -> return null
        }
        buffer = (buffer shl 5) or symbol
        bitsInBuffer += 5
        if (bitsInBuffer >= 8) {
            bitsInBuffer -= 8
            if (byteIndex >= bytes.size) return null
            bytes[byteIndex] = (buffer ushr bitsInBuffer).toByte()
            byteIndex += 1
            buffer = buffer and ((1 shl bitsInBuffer) - 1)
        }
    }
    return if (byteIndex == bytes.size) bytes.toLowerHexString() else null
}

private fun ByteArray.toLowerHexString(): String = buildString(size * 2) {
    for (byte in this@toLowerHexString) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4].lowercaseChar())
        append(HEX_DIGITS[value and 0x0f].lowercaseChar())
    }
}

internal fun buildP2pMagnetUri(infoHash: String, trackers: List<String>): String {
    val canonicalHash = canonicalP2pInfoHash(infoHash)
    val topic = if (canonicalHash.length == 40) {
        "urn:btih:$canonicalHash"
    } else {
        "urn:btmh:1220$canonicalHash"
    }
    val trackerParameters = trackers.filter(String::isNotBlank).distinct().joinToString("") { tracker ->
        "&tr=${tracker.encodeP2pQueryValue()}"
    }
    return "magnet:?xt=$topic$trackerParameters"
}

private fun String.encodeP2pQueryValue(): String = buildString {
    for (byte in this@encodeP2pQueryValue.encodeToByteArray()) {
        val value = byte.toInt() and 0xff
        if ((value in 'a'.code..'z'.code) ||
            (value in 'A'.code..'Z'.code) ||
            (value in '0'.code..'9'.code) ||
            value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code) {
            append(value.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

sealed class P2pStreamingState {
    data object Idle : P2pStreamingState()

    data class Connecting(
        val phase: String = "starting_engine",
        val downloadSpeed: Long = 0L,
        val uploadSpeed: Long = 0L,
        val peers: Int = 0,
        val seeds: Int = 0,
    ) : P2pStreamingState()

    data class Streaming(
        val localUrl: String,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val peers: Int,
        val seeds: Int,
        val bufferProgress: Float,
        val totalProgress: Float,
        val downloadedBytes: Long = 0L,
        val verifiedBytes: Long = 0L,
        val deliveredBytes: Long = 0L,
    ) : P2pStreamingState()

    data class Error(val message: String) : P2pStreamingState()
}

class P2pStreamingException(message: String) : Exception(message)

expect object P2pStreamingEngine {
    val state: StateFlow<P2pStreamingState>
    val cacheState: StateFlow<P2pCacheUiState>
    suspend fun startStream(request: P2pStreamRequest): String
    suspend fun clearCache(): P2pCacheClearResult
    fun stopStream()
    fun shutdown()
}

internal fun formatP2pSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> "${(bytesPerSec / 1_048_576.0).formatOneDecimal()} MB/s"
        bytesPerSec >= 1_024 -> "${(bytesPerSec / 1_024.0).formatNoDecimal()} KB/s"
        else -> "$bytesPerSec B/s"
    }
}

internal fun formatP2pMegabytes(bytes: Long): String =
    "${(bytes / 1_048_576.0).formatOneDecimal()} MB"

private fun Double.formatOneDecimal(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    val whole = rounded.toLong()
    val fraction = ((rounded - whole) * 10.0).toInt()
    return "$whole.$fraction"
}

private fun Double.formatNoDecimal(): String =
    kotlin.math.round(this).toInt().toString()
