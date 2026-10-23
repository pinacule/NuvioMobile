package com.nuvio.app.features.addons

internal expect object AddonStorage {
    fun loadInstalledAddonUrls(profileId: Int): List<String>
    fun saveInstalledAddonUrls(profileId: Int, urls: List<String>)
    fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean>
    fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>)
    /** One-time migration flag: true once the pre-installed default addons were seeded. */
    fun loadPreinstalledAddonsSeeded(profileId: Int): Boolean
    fun savePreinstalledAddonsSeeded(profileId: Int, seeded: Boolean)
    /**
     * Opaque per-profile addons sync bookkeeping (JSON owned by
     * AddonRepository: base revision, pending-push flag, last
     * server-acknowledged list). Persisted so a failed push is still
     * known-dirty after a process restart.
     */
    fun loadAddonSyncState(profileId: Int): String?
    fun saveAddonSyncState(profileId: Int, payload: String?)
}

data class RawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
    /** The response body before charset decoding. */
    val bodyBytes: ByteArray = body.encodeToByteArray(),
)

/** Default safety limit for generic and plugin-provided HTTP responses. */
internal const val DefaultRawHttpResponseMaxBytes = 1024 * 1024

expect suspend fun httpGetText(url: String): String

expect suspend fun httpPostJson(url: String, body: String): String

expect suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String

expect suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String

expect suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean = true,
    maxResponseBodyBytes: Int = DefaultRawHttpResponseMaxBytes,
    /** When present, sends these bytes verbatim instead of UTF-8 encoding [body]. */
    bodyBytes: ByteArray? = null,
): RawHttpResponse
