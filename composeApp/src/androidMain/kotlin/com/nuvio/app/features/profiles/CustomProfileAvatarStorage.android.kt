package com.nuvio.app.features.profiles

import android.content.Context
import java.io.File

internal actual object CustomProfileAvatarStorage {
    private var avatarDirectory: File? = null
    private val saveLock = Any()

    // Monotonic in-process floor so two saves racing the directory scan can
    // never land on the same revision (and thus the same URI).
    private var lastIssuedRevision = 0L

    fun initialize(context: Context) {
        // filesDir (not cacheDir): a user-picked picture cannot be re-downloaded,
        // so it must survive cache purges.
        avatarDirectory = context.filesDir.resolve("custom_profile_avatars")
    }

    actual fun loadCustomAvatar(profileIndex: Int): String? =
        currentAvatarFile(profileIndex)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.toURI()
            ?.toString()

    actual fun saveCustomAvatar(profileIndex: Int, bytes: ByteArray): String? {
        val directory = avatarDirectory ?: return null
        directory.mkdirs()
        synchronized(saveLock) {
            val existing = avatarFiles(profileIndex)
            val revision = maxOf(
                nextCustomAvatarRevision(existing.map { it.second.revision }),
                lastIssuedRevision + 1,
            )
            val file = directory.resolve(customAvatarFileName(profileIndex, revision))
            val temporary = directory.resolve(".${file.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            if (!file.isFile || file.length() == 0L) return null
            lastIssuedRevision = revision
            // The fresh file supersedes every earlier revision for this profile.
            existing.forEach { (oldFile, _) ->
                if (oldFile.name != file.name) oldFile.delete()
            }
            return file.toURI().toString()
        }
    }

    actual fun deleteCustomAvatar(profileIndex: Int) {
        avatarFiles(profileIndex).forEach { (file, _) -> file.delete() }
    }

    actual fun customAvatarProfileIndexes(): Set<Int> {
        val directory = avatarDirectory ?: return emptySet()
        return directory.listFiles().orEmpty().mapNotNull { file ->
            if (file.isFile) parseCustomAvatarFileName(file.name)?.profileIndex else null
        }.toSet()
    }

    /** All stored picture files for [profileIndex] (legacy + revisioned), paired with their refs. */
    private fun avatarFiles(profileIndex: Int): List<Pair<File, CustomAvatarFileRef>> {
        val directory = avatarDirectory ?: return emptyList()
        return directory.listFiles().orEmpty().mapNotNull { file ->
            val ref = parseCustomAvatarFileName(file.name) ?: return@mapNotNull null
            if (file.isFile && ref.profileIndex == profileIndex) file to ref else null
        }
    }

    private fun currentAvatarFile(profileIndex: Int): File? =
        avatarFiles(profileIndex).maxByOrNull { it.second.revision }?.first
}
