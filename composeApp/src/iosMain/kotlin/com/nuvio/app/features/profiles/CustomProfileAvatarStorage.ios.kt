package com.nuvio.app.features.profiles

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
internal actual object CustomProfileAvatarStorage {
    // Application Support (not Caches): a user-picked picture cannot be
    // re-downloaded, so it must survive cache purges.
    private val avatarDirectory = "${NSHomeDirectory()}/Library/Application Support/NuvioCustomAvatars"

    // Monotonic in-process floor so two saves racing the directory scan can
    // never land on the same revision (and thus the same URI).
    private var lastIssuedRevision = 0L

    actual fun loadCustomAvatar(profileIndex: Int): String? {
        val path = currentAvatarPath(profileIndex) ?: return null
        // Parity with Android/desktop: a missing or empty file counts as unset.
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        if (fileSize(path) <= 0L) return null
        return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
    }

    actual fun saveCustomAvatar(profileIndex: Int, bytes: ByteArray): String? {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = avatarDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val existing = avatarEntries(profileIndex)
        val revision = maxOf(
            nextCustomAvatarRevision(existing.map { it.second.revision }),
            lastIssuedRevision + 1,
        )
        val fileName = customAvatarFileName(profileIndex, revision)
        val path = "$avatarDirectory/$fileName"
        val file = fopen(path, "wb") ?: return null
        val written = try {
            if (bytes.isEmpty()) {
                true
            } else {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toLong() == bytes.size.toLong()
                }
            }
        } finally {
            fclose(file)
        }
        if (!written) return null
        lastIssuedRevision = revision
        // The fresh file supersedes every earlier revision for this profile.
        existing.forEach { (oldFileName, _) ->
            if (oldFileName != fileName) {
                NSFileManager.defaultManager.removeItemAtPath("$avatarDirectory/$oldFileName", null)
            }
        }
        return loadCustomAvatar(profileIndex)
    }

    actual fun deleteCustomAvatar(profileIndex: Int) {
        avatarEntries(profileIndex).forEach { (fileName, _) ->
            NSFileManager.defaultManager.removeItemAtPath("$avatarDirectory/$fileName", null)
        }
    }

    actual fun customAvatarProfileIndexes(): Set<Int> =
        avatarFileNames().mapNotNull { fileName ->
            parseCustomAvatarFileName(fileName)?.profileIndex
        }.toSet()

    private fun avatarFileNames(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(avatarDirectory, null)
            ?.filterIsInstance<String>()
            .orEmpty()

    /** All stored picture files for [profileIndex] (legacy + revisioned), paired with their refs. */
    private fun avatarEntries(profileIndex: Int): List<Pair<String, CustomAvatarFileRef>> =
        avatarFileNames().mapNotNull { fileName ->
            val ref = parseCustomAvatarFileName(fileName) ?: return@mapNotNull null
            if (ref.profileIndex == profileIndex) fileName to ref else null
        }

    private fun currentAvatarPath(profileIndex: Int): String? =
        avatarEntries(profileIndex)
            .maxByOrNull { it.second.revision }
            ?.let { (fileName, _) -> "$avatarDirectory/$fileName" }

    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
        val value = attrs?.get("NSFileSize")
        return when (value) {
            is Long -> value
            is Number -> value.toLong()
            else -> 0L
        }
    }
}
