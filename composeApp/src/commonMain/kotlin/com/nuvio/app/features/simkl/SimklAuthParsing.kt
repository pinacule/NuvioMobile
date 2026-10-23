package com.nuvio.app.features.simkl

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

internal const val SIMKL_AUTHORIZATION_TIMEOUT_MS = 5L * 60L * 1_000L

internal fun parseSimklAuthCallback(
    callbackUrl: String,
    redirectUri: String,
): SimklAuthCallback {
    if (callbackUrl != redirectUri && !callbackUrl.startsWith("$redirectUri?")) {
        return SimklAuthCallback.NotSimkl
    }
    // Incomplete or unparseable callbacks are ignored (NotSimkl), never
    // treated as a failed sign-in: any app or web page can fire a
    // nuvio://auth/simkl link, and a stray callback with a blank/missing
    // code or state must not destroy a pending authorization that is
    // still waiting for its legitimate callback.
    val parsed = runCatching { Url(callbackUrl) }.getOrNull() ?: return SimklAuthCallback.NotSimkl
    val code = parsed.parameters["code"].orEmpty().trim()
    val state = parsed.parameters["state"].orEmpty().trim()
    if (code.isBlank() || state.isBlank()) return SimklAuthCallback.NotSimkl
    return SimklAuthCallback.AuthorizationCode(code = code, state = state)
}

internal fun isSimklAuthorizationExpired(
    startedAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = startedAtEpochMs == null ||
    nowEpochMs < startedAtEpochMs ||
    nowEpochMs - startedAtEpochMs > SIMKL_AUTHORIZATION_TIMEOUT_MS

internal fun constantTimeEquals(left: String, right: String): Boolean {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    val length = maxOf(leftBytes.size, rightBytes.size)
    var difference = leftBytes.size xor rightBytes.size
    for (index in 0 until length) {
        val leftByte = leftBytes.getOrElse(index) { 0 }.toInt()
        val rightByte = rightBytes.getOrElse(index) { 0 }.toInt()
        difference = difference or (leftByte xor rightByte)
    }
    return difference == 0
}

internal fun buildSimklAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    appName: String,
    appVersion: String,
    material: SimklPkceMaterial,
): String = buildString {
    append(SIMKL_AUTHORIZE_URL)
    append("?response_type=code")
    append("&client_id=")
    append(clientId.encodeURLParameter())
    append("&redirect_uri=")
    append(redirectUri.encodeURLParameter())
    append("&code_challenge=")
    append(material.challenge.encodeURLParameter())
    append("&code_challenge_method=S256")
    append("&state=")
    append(material.state.encodeURLParameter())
    append("&app-name=")
    append(appName.encodeURLParameter())
    append("&app-version=")
    append(appVersion.encodeURLParameter())
    // Simkl grants read-only tokens when scope is omitted; the app reads
    // (library, watch progress) and writes (scrobbles, history), so request both.
    append("&scope=")
    append("media:read media:write".encodeURLParameter())
}
