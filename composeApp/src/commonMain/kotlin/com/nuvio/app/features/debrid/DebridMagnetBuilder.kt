package com.nuvio.app.features.debrid

import com.nuvio.app.features.p2p.canonicalP2pInfoHashOrNull
import com.nuvio.app.features.streams.StreamItem

internal object DebridMagnetBuilder {
    fun fromStream(stream: StreamItem): String? {
        stream.torrentMagnetUri?.takeIf { it.isNotBlank() }?.let { return it }
        val rawHash = stream.p2pInfoHash ?: return null
        // Canonicalize base32 hashes to hex: some debrid resolvers only accept
        // the hex btih form. Unparseable values keep the raw form, as before.
        val hash = canonicalP2pInfoHashOrNull(rawHash) ?: rawHash
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            stream.behaviorHints.filename
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { filename ->
                    append("&dn=")
                    append(encodePathSegment(filename))
                }
            stream.sources
                .mapNotNull(::trackerUrl)
                .distinct()
                .forEach { tracker ->
                    append("&tr=")
                    append(encodePathSegment(tracker))
                }
        }
    }

    private fun trackerUrl(source: String): String? {
        val value = source.trim()
        if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) return null
        return value
            .removePrefix("tracker:")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
