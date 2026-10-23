package com.nuvio.app.features.party

import com.nuvio.app.features.p2p.canonicalP2pInfoHashOrNull
import com.nuvio.app.features.streams.StreamItem

internal fun PartySource.matches(stream: StreamItem): Boolean {
    // Canonicalize both sides: the same torrent can be reported as a hex hash
    // by one peer/addon and as base32 by another, and raw comparison would
    // call those different sources. Unparseable values fall back to the raw
    // lowercase comparison, as before.
    val hash = canonicalP2pInfoHashOrNull(infoHash)
        ?: infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamHash = canonicalP2pInfoHashOrNull(stream.p2pInfoHash)
        ?: stream.p2pInfoHash?.lowercase()
    if (hash != null && streamHash != null) {
        val index = fileIdx
        val streamIndex = stream.p2pFileIdx
        return hash == streamHash && (index == null || streamIndex == null || index == streamIndex)
    }
    val file = filename?.trim()?.takeIf { it.isNotEmpty() }
    val streamFile = (stream.behaviorHints.filename ?: stream.clientResolve?.filename)?.trim()
    if (file != null && !streamFile.isNullOrEmpty()) return file.equals(streamFile, ignoreCase = true)
    val label = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return stream.addonId == addonId && stream.streamLabel.trim().equals(label, ignoreCase = true)
}
