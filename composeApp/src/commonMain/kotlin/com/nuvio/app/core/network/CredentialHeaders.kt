package com.nuvio.app.core.network

/**
 * Header names that can carry credentials for a stream or API origin. Stream
 * headers come from addon metadata and routinely include a debrid token in
 * `Authorization`; everything in this set must stay scoped to the host the
 * headers were issued for and must never ride along to another host (a
 * subtitle CDN, a separate audio host, a redirect target).
 */
private val credentialHeaderNames = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "x-api-key",
    "api-key",
    "apikey",
    "x-auth-token",
    "x-access-token",
    "x-session-token",
    "x-csrf-token",
    "x-xsrf-token",
)

/** True when [name] (any case) is a header that can carry credentials. */
internal fun isCredentialHeader(name: String): Boolean =
    name.trim().lowercase() in credentialHeaderNames

/** This header map with every credential header removed. */
internal fun Map<String, String>.withoutCredentialHeaders(): Map<String, String> =
    filterKeys { key -> !isCredentialHeader(key) }
