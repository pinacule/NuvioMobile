package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.BufferedReader
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Desktop playback by launching the system `mpv` binary (PKGBUILD depends on
 * `mpv`) and driving it through its JSON IPC protocol over a Unix socket.
 *
 * Video renders in mpv's own window; this surface shows a placeholder with
 * playback status. In-window video (libmpv embedding) is a future step.
 */
@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    playbackEngine: AndroidPlaybackEngine?,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val controllerRef = remember { AtomicReference<MpvIpcController?>(null) }

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (errorMessage != null) {
                Text("Playback failed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    if (snapshot.isPlaying) "Playing in mpv" else "Starting mpv…",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Video plays in the mpv window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.durationMs > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatMpvClock(snapshot.positionMs)} / ${formatMpvClock(snapshot.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    LaunchedEffect(sourceUrl, initialPositionRequestKey) {
        val controller = MpvIpcController()
        controllerRef.set(controller)
        try {
            controller.start(
                sourceUrl = sourceUrl,
                headers = sanitizePlaybackHeaders(sourceHeaders),
                subtitles = externalSubtitles,
                playWhenReady = playWhenReady,
                initialPositionMs = initialPositionMs,
            )
            onControllerReady(controller)
            if (initialPositionRequestKey != null) {
                onInitialPositionHandled(initialPositionRequestKey, true)
            }
            controller.snapshots.collect {
                snapshot = it
                onSnapshot(it)
            }
        } catch (e: Exception) {
            if (e is MpvLaunchException) {
                errorMessage = e.message
                onError(e.message)
            } else {
                onError(e.message)
            }
        }
    }

    DisposableEffect(sourceUrl, initialPositionRequestKey) {
        onDispose {
            controllerRef.getAndSet(null)?.close()
        }
    }
}

private class MpvLaunchException(message: String) : Exception(message)

private fun formatMpvClock(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private class MpvIpcController : PlayerEngineController {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicInteger(1)

    private var process: Process? = null
    private var channel: SocketChannel? = null
    private var writer: java.io.BufferedWriter? = null
    private var readerThread: Thread? = null
    @Volatile private var closed = false

    private val _snapshots = MutableSharedFlow<PlayerPlaybackSnapshot>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snapshots: SharedFlow<PlayerPlaybackSnapshot> = _snapshots.asSharedFlow()

    private val _snapshotState = MutableStateFlow(PlayerPlaybackSnapshot())
    val snapshotState: StateFlow<PlayerPlaybackSnapshot> = _snapshotState.asStateFlow()

    // Last observed mpv properties.
    @Volatile private var timePos: Double? = null
    @Volatile private var duration: Double? = null
    @Volatile private var paused: Boolean = true
    @Volatile private var speed: Double = 1.0
    @Volatile private var trackList: JsonArray? = null

    suspend fun start(
        sourceUrl: String,
        headers: Map<String, String>,
        subtitles: List<StreamSubtitle>,
        playWhenReady: Boolean,
        initialPositionMs: Long?,
    ) = withContext(Dispatchers.IO) {
        val binary = findMpvBinary()
            ?: throw MpvLaunchException(
                "mpv is not installed. Install it with: sudo pacman -S mpv",
            )
        val socketPath = File(
            System.getProperty("java.io.tmpdir"),
            "nuvio-mpv-${UUID.randomUUID().toString().take(8)}.sock",
        )
        if (socketPath.exists()) socketPath.delete()

        val args = mutableListOf(
            binary.absolutePath,
            "--no-terminal",
            "--input-ipc-server=${socketPath.absolutePath}",
            "--title=Nuvio",
        )
        if (headers.isNotEmpty()) {
            val fields = headers.entries.joinToString(",") { (k, v) -> "$k: $v" }
            args += "--http-header-fields=$fields"
        }
        if (!playWhenReady) args += "--pause"
        if (initialPositionMs != null && initialPositionMs > 0) {
            args += "--start=${initialPositionMs / 1000.0}"
        }
        subtitles.forEach { args += "--sub-file=${it.url}" }
        args += sourceUrl

        val logFile = File(System.getProperty("user.home"), ".config/nuvio/mpv.log")
            .apply { parentFile.mkdirs() }
        process = ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()

        // Wait for the IPC socket to appear (mpv creates it on startup).
        val deadline = System.currentTimeMillis() + 10_000
        var connected = false
        while (System.currentTimeMillis() < deadline && !closed) {
            try {
                val address = UnixDomainSocketAddress.of(socketPath.toPath())
                channel = SocketChannel.open(address)
                connected = true
                break
            } catch (_: Exception) {
                Thread.sleep(100)
                if (process?.isAlive != true) break
            }
        }
        if (!connected) {
            close()
            val exit = try { process?.exitValue() } catch (_: Exception) { null }
            throw MpvLaunchException("Could not talk to mpv (exit=$exit). See ~/.config/nuvio/mpv.log")
        }

        val ch = channel!!
        writer = Channels.newWriter(ch, StandardCharsets.UTF_8).buffered()
        val reader: BufferedReader = Channels.newReader(ch, StandardCharsets.UTF_8).buffered()
        readerThread = thread(name = "nuvio-mpv-ipc", isDaemon = true) {
            try {
                while (!closed) {
                    val line = reader.readLine() ?: break
                    handleMessage(line)
                }
            } catch (_: Exception) {
            } finally {
                if (!closed) {
                    // mpv died unexpectedly.
                    _snapshots.tryEmit(_snapshotState.value.copy(isPlaying = false))
                }
            }
        }

        observe(1, "time-pos")
        observe(2, "duration")
        observe(3, "pause")
        observe(4, "speed")
        observe(5, "eof-reached")
        observe(6, "track-list")
        paused = !playWhenReady
        publishSnapshot(loading = true)
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            send("quit")
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        process = null
    }

    // ---- PlayerEngineController ----

    override fun play() {
        setProperty("pause", "false")
        paused = false
        publishSnapshot()
    }

    override fun pause() {
        setProperty("pause", "true")
        paused = true
        publishSnapshot()
    }

    override fun seekTo(positionMs: Long) {
        send("seek", (positionMs / 1000.0).toString(), "absolute")
    }

    override fun seekBy(offsetMs: Long) {
        send("seek", (offsetMs / 1000.0).toString(), "relative")
    }

    override fun retry() {
        send("seek", "0", "absolute")
        play()
    }

    override fun setPlaybackSpeed(speed: Float) {
        setProperty("speed", speed.toString())
        this.speed = speed.toDouble()
        publishSnapshot()
    }

    override fun setMuted(muted: Boolean) {
        setProperty("mute", if (muted) "yes" else "no")
    }

    override fun getAudioTracks(): List<AudioTrack> = tracksOfType("audio").mapIndexed { index, track ->
        AudioTrack(
            index = index,
            id = track.mpvId.toString(),
            label = track.label,
            language = track.lang,
            isSelected = track.selected,
        )
    }

    override fun getSubtitleTracks(): List<SubtitleTrack> = tracksOfType("sub").mapIndexed { index, track ->
        SubtitleTrack(
            index = index,
            id = track.mpvId.toString(),
            label = track.label,
            language = track.lang,
            isSelected = track.selected,
        )
    }

    override fun applyAudioLanguagePreferences(languages: List<String>) {
        if (languages.isNotEmpty()) setProperty("alang", languages.joinToString(","))
    }

    override fun selectAudioTrack(index: Int) {
        tracksOfType("audio").getOrNull(index)?.let { setProperty("aid", it.mpvId.toString()) }
    }

    override fun selectSubtitleTrack(index: Int) {
        tracksOfType("sub").getOrNull(index)?.let { setProperty("sid", it.mpvId.toString()) }
    }

    override fun setSubtitleUri(url: String) {
        send("sub-add", url)
    }

    override fun clearExternalSubtitle() {
        setProperty("sid", "no")
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        selectSubtitleTrack(trackIndex)
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        setProperty("sub-delay", (delayMs / 1000.0).toString())
    }

    override fun applySubtitlePreferences(
        preferredLanguage: String,
        secondaryPreferredLanguage: String?,
        useForcedSubtitles: Boolean,
        autoSelectionApplied: Boolean,
        hasActiveSubtitle: Boolean,
        useCustomSubtitles: Boolean,
    ) {
        val langs = listOfNotNull(
            preferredLanguage.takeIf { it.isNotBlank() },
            secondaryPreferredLanguage?.takeIf { it.isNotBlank() },
        )
        if (langs.isNotEmpty()) setProperty("slang", langs.joinToString(","))
    }

    // ---- IPC plumbing ----

    private data class MpvTrack(
        val mpvId: Int,
        val label: String,
        val lang: String?,
        val selected: Boolean,
    )

    private fun tracksOfType(type: String): List<MpvTrack> {
        val list = trackList ?: return emptyList()
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != type) return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            val lang = (obj["lang"] as? JsonPrimitive)?.contentOrNull
            val selected = (obj["selected"] as? JsonPrimitive)?.booleanOrNull == true
            MpvTrack(id, title ?: lang ?: "$type $id", lang, selected)
        }
    }

    private fun findMpvBinary(): File? {
        val candidates = listOf(
            "/usr/bin/mpv",
            "/usr/local/bin/mpv",
            File(System.getProperty("user.home"), ".local/bin/mpv").absolutePath,
        )
        candidates.map(::File).firstOrNull { it.canExecute() }?.let { return it }
        return try {
            val proc = ProcessBuilder("which", "mpv").redirectErrorStream(true).start()
            val path = proc.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()
            proc.waitFor()
            path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.canExecute() }
        } catch (_: Exception) {
            null
        }
    }

    private fun observe(id: Int, property: String) {
        sendRaw("""{"command":["observe_property",$id,"${property}"],"request_id":${requestId.getAndIncrement()}}""")
    }

    private fun setProperty(name: String, value: String) {
        // Values are passed raw; quote strings.
        val encoded = if (value == "true" || value == "false" || value.toDoubleOrNull() != null) {
            value
        } else {
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        sendRaw("""{"command":["set_property","$name",$encoded],"request_id":${requestId.getAndIncrement()}}""")
    }

    private fun send(command: String, vararg args: String) {
        val encodedArgs = args.joinToString(",") { arg ->
            if (arg.toDoubleOrNull() != null) arg
            else "\"${arg.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        sendRaw("""{"command":["$command"${if (encodedArgs.isNotEmpty()) ",$encodedArgs" else ""}],"request_id":${requestId.getAndIncrement()}}""")
    }

    @Synchronized
    private fun sendRaw(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (_: Exception) {
        }
    }

    private fun handleMessage(line: String) {
        val element = try {
            json.parseToJsonElement(line)
        } catch (_: Exception) {
            return
        } as? JsonObject ?: return
        if ((element["event"] as? JsonPrimitive)?.contentOrNull == "property-change") {
            val id = (element["id"] as? JsonPrimitive)?.intOrNull ?: return
            val data = element["data"]
            when (id) {
                1 -> {
                    timePos = (data as? JsonPrimitive)?.doubleOrNull
                    publishSnapshot()
                }
                2 -> {
                    duration = (data as? JsonPrimitive)?.doubleOrNull
                    publishSnapshot()
                }
                3 -> {
                    paused = (data as? JsonPrimitive)?.booleanOrNull ?: paused
                    publishSnapshot()
                }
                4 -> {
                    speed = (data as? JsonPrimitive)?.doubleOrNull ?: speed
                    publishSnapshot()
                }
                5 -> {
                    if ((data as? JsonPrimitive)?.booleanOrNull == true) {
                        _snapshots.tryEmit(_snapshotState.value.copy(isEnded = true, isPlaying = false))
                        _snapshotState.value = _snapshotState.value.copy(isEnded = true, isPlaying = false)
                    }
                }
                6 -> {
                    trackList = data as? JsonArray
                }
            }
        }
    }

    private fun publishSnapshot(loading: Boolean = false) {
        val posMs = ((timePos ?: 0.0) * 1000).toLong().coerceAtLeast(0)
        val durMs = ((duration ?: 0.0) * 1000).toLong().coerceAtLeast(0)
        val snapshot = PlayerPlaybackSnapshot(
            isLoading = loading || (durMs == 0L && !paused),
            isPlaying = !paused,
            durationMs = durMs,
            positionMs = posMs,
            bufferedPositionMs = posMs,
            playbackSpeed = speed.toFloat(),
        )
        _snapshotState.value = snapshot
        _snapshots.tryEmit(snapshot)
    }
}
