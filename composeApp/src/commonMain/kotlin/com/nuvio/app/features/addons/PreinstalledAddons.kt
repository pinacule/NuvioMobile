package com.nuvio.app.features.addons

/**
 * Addons that ship as defaults. On first run (one-time migration in [AddonRepository]),
 * each of these is installed unless an addon of the same family (matched by URL host,
 * so any configured variant counts) is already installed. The user's existing installs
 * are always left untouched, and the seeded addons stay fully removable/editable in the
 * Addons screen afterwards.
 */
const val TORRENTIO_PREINSTALLED_MANIFEST_URL: String =
    "https://torrentio.strem.fun/qualityfilter=brremux,4k,dolbyvision,threed,unknown,cam,scr,other|limit=2/manifest.json"

const val CINEMETA_PREINSTALLED_MANIFEST_URL: String =
    "https://v3-cinemeta.strem.io/manifest.json"

/** The default addons, in the order they are seeded. */
fun preinstalledAddonManifestUrls(): List<String> =
    listOf(TORRENTIO_PREINSTALLED_MANIFEST_URL, CINEMETA_PREINSTALLED_MANIFEST_URL)

/**
 * Host part of an addon manifest URL, lowercased. Addon families are matched by host so
 * that a differently-configured variant (e.g. Torrentio with other quality filters)
 * still counts as "already installed".
 */
internal fun addonManifestUrlHost(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val afterScheme = trimmed.substringAfter("://", trimmed)
    return afterScheme
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .lowercase()
}

/**
 * Returns the subset of [preinstalledAddonManifestUrls] whose addon family is not
 * represented in [installedManifestUrls]. Pure logic — safe to unit test on JVM.
 */
fun missingPreinstalledAddonUrls(installedManifestUrls: List<String>): List<String> {
    val installedHosts = installedManifestUrls.map(::addonManifestUrlHost).toSet()
    return preinstalledAddonManifestUrls().filter { candidate ->
        addonManifestUrlHost(candidate) !in installedHosts
    }
}
