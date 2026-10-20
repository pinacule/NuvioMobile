package com.nuvio.app.features.profiles

import java.io.File

internal actual object CustomProfileAvatarStorage {
    private val avatarDirectory: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".config").absolutePath
        File(base, "nuvio/custom_profile_avatars")
    }
    private val saveLock = Any()

    // Monotonic in-process floor so two saves racing the directory scan can
    // never land on the same revision (and thus the same URI).
    private var lastIssuedRevision = 0L

    actual fun loadCustomAvatar(profileIndex: Int): String? =
        currentAvatarFile(profileIndex)
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.toURI()
            ?.toString()

    actual fun saveCustomAvatar(profileIndex: Int, bytes: ByteArray): String? {
        val directory = avatarDirectory
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

    actual fun customAvatarProfileIndexes(): Set<Int> =
        avatarDirectory.listFiles().orEmpty().mapNotNull { file ->
            if (file.isFile) parseCustomAvatarFileName(file.name)?.profileIndex else null
        }.toSet()

    /** All stored picture files for [profileIndex] (legacy + revisioned), paired with their refs. */
    private fun avatarFiles(profileIndex: Int): List<Pair<File, CustomAvatarFileRef>> =
        avatarDirectory.listFiles().orEmpty().mapNotNull { file ->
            val ref = parseCustomAvatarFileName(file.name) ?: return@mapNotNull null
            if (file.isFile && ref.profileIndex == profileIndex) file to ref else null
        }

    private fun currentAvatarFile(profileIndex: Int): File? =
        avatarFiles(profileIndex).maxByOrNull { it.second.revision }?.first
}
