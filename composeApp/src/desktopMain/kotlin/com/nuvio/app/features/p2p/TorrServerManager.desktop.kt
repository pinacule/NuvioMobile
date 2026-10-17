package com.nuvio.app.features.p2p

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder

/**
 * Manages the local TorrServer subprocess and its HTTP API.
 *
 * TorrServer is not bundled: on Arch it comes from the AUR `torrserver-bin`
 * package (`/usr/bin/torrserver`). If `NUVIO_TORRSERVER_URL` is set, no
 * subprocess is started and that server is used as-is.
 *
 * API verified against TorrServer MatriX.147:
 * - `GET /echo` -> version string (liveness)
 * - `POST /torrents {"action":"add","link":..,"title":..}` -> torrent JSON
 * - `POST /torrents {"action":"get","hash":..}` -> torrent JSON
 * - `POST /torrents {"action":"drop","hash":..}` -> empty
 * - `POST /torrents {"action":"list"}` -> JSON array
 * - `GET /stream/<file>?link=<hash>&index=<id>&play` -> media bytes
 */
internal class TorrServerManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = createApiHttpClient()

    val baseUrl: String =
        System.getenv("NUVIO_TORRSERVER_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: "http://127.0.0.1:8090"

    private val managesProcess = System.getenv("NUVIO_TORRSERVER_URL").isNullOrBlank()
    private var process: Process? = null

    private val dataDir: File =
        File(System.getProperty("user.home"), ".config/nuvio/torrserver").apply { mkdirs() }

    /** Starts the subprocess if needed and waits until the API answers. */
    suspend fun ensureRunning() = withContext(Dispatchers.IO) {
        if (isAlive()) return@withContext
        if (!managesProcess) {
            throw IllegalStateException("TorrServer is not reachable at $baseUrl")
        }
        val binary = findBinary()
            ?: throw IllegalStateException(
                "torrserver binary not found. Install the AUR package 'torrserver-bin', " +
                    "or point NUVIO_TORRSERVER_URL at a running TorrServer.",
            )
        val logFile = File(dataDir, "torrserver.log")
        process = ProcessBuilder(binary.absolutePath)
            .directory(dataDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        withTimeout(15_000) {
            while (!isAlive()) delay(250)
        }
    }

    fun shutdown() {
        try {
            process?.destroy()
            process = null
        } catch (_: Exception) {
        }
    }

    private suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = http.post("$baseUrl/echo") {}
            // /echo answers to any method with the version string.
            response.status.isSuccess() && response.bodyAsText().isNotBlank()
        } catch (_: Exception) {
            // Some builds only serve GET /echo; try that too.
            try {
                val response = http.get("$baseUrl/echo")
                response.status.isSuccess()
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun findBinary(): File? {
        System.getenv("NUVIO_TORRSERVER_BIN")?.trim()?.takeIf { it.isNotBlank() }?.let {
            File(it).takeIf { f -> f.canExecute() }?.let { f -> return f }
        }
        val candidates = listOf(
            "/usr/bin/torrserver",
            "/usr/local/bin/torrserver",
            File(System.getProperty("user.home"), ".local/bin/torrserver").absolutePath,
            "/opt/torrserver/torrserver",
        )
        candidates.map(::File).firstOrNull { it.canExecute() }?.let { return it }
        return try {
            val which = ProcessBuilder("which", "torrserver")
                .redirectErrorStream(true)
                .start()
            val path = which.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()
            which.waitFor()
            path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.canExecute() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun torrentsAction(body: JsonObject): String = withContext(Dispatchers.IO) {
        val response = http.post("$baseUrl/torrents") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("TorrServer /torrents failed: ${response.status}")
        }
        response.bodyAsText()
    }

    suspend fun addTorrent(magnet: String, title: String): JsonObject {
        val raw = torrentsAction(
            buildJsonObject {
                put("action", "add")
                put("link", magnet)
                put("title", title)
                put("poster", "")
                put("data", "")
            },
        )
        return json.parseToJsonElement(raw) as JsonObject
    }

    suspend fun getTorrent(hash: String): JsonObject? {
        return try {
            val raw = torrentsAction(
                buildJsonObject {
                    put("action", "get")
                    put("hash", hash)
                },
            )
            json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    suspend fun dropTorrent(hash: String) {
        try {
            torrentsAction(
                buildJsonObject {
                    put("action", "drop")
                    put("hash", hash)
                },
            )
        } catch (_: Exception) {
        }
    }

    suspend fun listTorrents(): List<JsonObject> {
        return try {
            val raw = torrentsAction(buildJsonObject { put("action", "list") })
            (json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it as? JsonObject } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun streamUrl(hash: String, fileId: Long, fileName: String): String {
        val encodedName = URLEncoder.encode(fileName.substringAfterLast('/'), Charsets.UTF_8)
            .replace("+", "%20")
        return "$baseUrl/stream/$encodedName?link=$hash&index=$fileId&play"
    }

    companion object {
        fun stringField(obj: JsonObject, vararg names: String): String? =
            names.firstNotNullOfOrNull { (obj[it] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

        fun longField(obj: JsonObject, vararg names: String): Long? =
            names.firstNotNullOfOrNull { (obj[it] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }

        fun intField(obj: JsonObject, vararg names: String): Int? =
            names.firstNotNullOfOrNull { (obj[it] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
    }
}
