package com.nuvio.app.features.addons

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.storage.DesktopPreferences
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayOutputStream
import java.net.URL

internal actual object AddonStorage {
    private const val addonUrlsKey = "installed_manifest_urls"
    private const val addonEnabledStatesKey = "installed_manifest_enabled_states"
    private fun urlsKey(profileId: Int) = "${addonUrlsKey}_$profileId"
    private fun enabledStatesKey(profileId: Int) = "${addonEnabledStatesKey}_$profileId"

    private val prefs = DesktopPreferences("addon_storage")

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        prefs.getString(urlsKey(profileId))
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        prefs.putString(urlsKey(profileId), urls.joinToString(separator = "\n"))
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        prefs.getString(enabledStatesKey(profileId))
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseEnabledStateLine)
            .toMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val payload = states.entries.joinToString(separator = "\n") { (url, enabled) ->
            "$url\t$enabled"
        }
        prefs.putString(enabledStatesKey(profileId), payload)
    }
}

private fun parseEnabledStateLine(line: String): Pair<String, Boolean>? {
    val url = line.substringBefore("\t").trim().takeIf { it.isNotEmpty() } ?: return null
    val rawEnabled = line.substringAfter("\t", "true").trim().lowercase()
    val enabled = when (rawEnabled) {
        "false" -> false
        else -> true
    }
    return url to enabled
}

private val addonHttpClient by lazy { createApiHttpClient() }

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private data class FetchedResponse(
    val response: HttpResponse,
    val finalUrl: String,
)

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

/**
 * Issues the request through the shared API client, following redirects manually
 * (up to 10 hops) so the effective behavior matches Android's redirect-following
 * client regardless of the shared client's own redirect configuration.
 */
private suspend fun fetchWithRedirects(
    method: String,
    url: String,
    headers: Map<String, String>,
    bodyBytes: ByteArray?,
    followRedirects: Boolean,
): FetchedResponse = withContext(Dispatchers.IO) {
    val sanitizedHeaders = headers.withoutAcceptEncoding()
    var currentMethod = method.uppercase()
    var currentUrl = url
    var currentBody = bodyBytes
    var redirects = 0
    while (true) {
        val response: HttpResponse = addonHttpClient.request(currentUrl) {
            this.method = HttpMethod.parse(currentMethod)
            sanitizedHeaders.forEach { (key, value) -> header(key, value) }
            if (currentBody != null && requestAllowsBody(currentMethod)) {
                if (sanitizedHeaders.getHeaderIgnoreCase("Content-Type") == null) {
                    val defaultType = if (currentMethod == "POST") {
                        "application/x-www-form-urlencoded"
                    } else {
                        "application/json"
                    }
                    contentType(ContentType.parse(defaultType))
                }
                setBody(currentBody!!)
            }
        }
        val status = response.status.value
        val location = response.headers[HttpHeaders.Location]
        if (followRedirects && status in 300..399 && location != null && redirects < 10) {
            // Drain the (usually empty) redirect body so the connection can be reused.
            runCatching { response.body<ByteArray>() }
            redirects++
            currentUrl = URL(URL(currentUrl), location).toString()
            // Mirror OkHttp: 303 always becomes GET; 301/302 turn POST into GET; 307/308 keep everything.
            if (status == 303 || (status in listOf(301, 302) && currentMethod == "POST")) {
                currentMethod = "GET"
                currentBody = null
            }
            continue
        }
        return@withContext FetchedResponse(response, currentUrl)
    }
}

private fun decodeBody(bytes: ByteArray, response: HttpResponse): String {
    val charset = response.contentType()?.charset() ?: Charsets.UTF_8
    return runCatching { String(bytes, charset) }.getOrElse { String(bytes, Charsets.UTF_8) }
}

private suspend fun readAtMostBytes(response: HttpResponse, maxBytes: Int): LimitedReadResult {
    val channel = response.bodyAsChannel()
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = channel.readAvailable(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = channel.readAvailable(buffer, 0, 1) != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String {
    val fetched = fetchWithRedirects(
        method = method,
        url = url,
        headers = headers,
        bodyBytes = body.toByteArray(Charsets.UTF_8).takeIf { requestAllowsBody(method) },
        followRedirects = true,
    )
    val response = fetched.response
    val payload = decodeBody(response.body(), response)
    if (!response.status.isSuccess()) {
        error(getString(Res.string.network_request_failed_http, response.status.value))
    }
    if (payload.isBlank()) {
        throw IllegalStateException(getString(Res.string.network_empty_response_body))
    }
    return payload
}

actual suspend fun httpGetText(url: String): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
    bodyBytes: ByteArray?,
): RawHttpResponse {
    val fetched = fetchWithRedirects(
        method = method,
        url = url,
        headers = headers,
        bodyBytes = (bodyBytes ?: body.toByteArray(Charsets.UTF_8)).takeIf { requestAllowsBody(method) },
        followRedirects = followRedirects,
    )
    val response = fetched.response
    val readResult = readAtMostBytes(response, maxResponseBodyBytes.coerceAtLeast(0))
    val decoded = decodeBody(readResult.bytes, response)
    return RawHttpResponse(
        status = response.status.value,
        statusText = response.status.description,
        url = fetched.finalUrl,
        body = if (readResult.truncated) "$decoded\n...[truncated]" else decoded,
        bodyBytes = readResult.bytes,
        headers = response.headers.entries().associate { (name, values) ->
            name.lowercase() to values.joinToString(",")
        },
    )
}
