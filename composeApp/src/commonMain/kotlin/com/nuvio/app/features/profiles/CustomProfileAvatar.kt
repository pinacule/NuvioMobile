package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * On-device profile picture chosen by the user from their photo library.
 *
 * Unlike catalog avatars and avatar URLs, a custom picture is a local file:
 * it only exists on the device where it was picked, is never synced, and any
 * surface rendering a profile avatar must fall back to the regular avatar
 * (catalog image / URL / initial letter) when no local file is present.
 *
 * Every save writes a NEW revisioned file (`profile_<index>_<revision>.jpg`)
 * instead of overwriting a fixed name: the URI changes on every pick, so
 * state flows re-emit and image caches keyed by URI can never resurrect the
 * previous picture. Saves written before revisions existed use the legacy
 * plain name (`profile_<index>.jpg`), which counts as revision 0.
 */
internal expect object CustomProfileAvatarStorage {
    /** File URI for the profile's current custom picture, or null when unset or the file is gone. */
    fun loadCustomAvatar(profileIndex: Int): String?

    /** Persists [bytes] as the profile's custom picture under a fresh revision and returns its file URI. */
    fun saveCustomAvatar(profileIndex: Int, bytes: ByteArray): String?

    /** Deletes every stored custom picture file for the profile (any revision). */
    fun deleteCustomAvatar(profileIndex: Int)

    /** Profile indexes that currently have at least one stored custom picture file (any revision). */
    fun customAvatarProfileIndexes(): Set<Int>
}

/** A stored custom picture file: which profile it belongs to and its save revision (0 = legacy name). */
internal data class CustomAvatarFileRef(
    val profileIndex: Int,
    val revision: Long,
)

internal fun customAvatarFileName(profileIndex: Int, revision: Long): String =
    if (revision <= 0L) "profile_$profileIndex.jpg" else "profile_${profileIndex}_$revision.jpg"

internal fun parseCustomAvatarFileName(fileName: String): CustomAvatarFileRef? {
    if (!fileName.startsWith("profile_") || !fileName.endsWith(".jpg")) return null
    val parts = fileName.removePrefix("profile_").removeSuffix(".jpg").split("_")
    return when (parts.size) {
        1 -> parts[0].toIntOrNull()?.let { CustomAvatarFileRef(profileIndex = it, revision = 0L) }
        2 -> {
            val profileIndex = parts[0].toIntOrNull() ?: return null
            val revision = parts[1].toLongOrNull() ?: return null
            CustomAvatarFileRef(profileIndex = profileIndex, revision = revision)
        }
        else -> null
    }
}

/** The revision the next save must use so its URI differs from every previous save. */
internal fun nextCustomAvatarRevision(existingRevisions: Collection<Long>): Long =
    (existingRevisions.maxOrNull() ?: 0L) + 1L

/** Stored picture indexes whose profile no longer exists; their files must be swept. */
internal fun orphanedCustomAvatarIndexes(
    storedIndexes: Set<Int>,
    existingProfileIndexes: Set<Int>,
): Set<Int> = storedIndexes - existingProfileIndexes

object CustomProfileAvatarRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _customAvatars = MutableStateFlow<Map<Int, String>>(emptyMap())
    val customAvatars: StateFlow<Map<Int, String>> = _customAvatars.asStateFlow()

    /** Loads (or reloads) the stored picture URI for [profileIndex] from disk. */
    fun refresh(profileIndex: Int) {
        scope.launch {
            val uri = CustomProfileAvatarStorage.loadCustomAvatar(profileIndex)
            _customAvatars.update { current ->
                if (uri == null) {
                    if (profileIndex in current) current - profileIndex else current
                } else if (current[profileIndex] == uri) {
                    current
                } else {
                    current + (profileIndex to uri)
                }
            }
        }
    }

    fun setCustomAvatar(profileIndex: Int, bytes: ByteArray) {
        scope.launch {
            val uri = CustomProfileAvatarStorage.saveCustomAvatar(profileIndex, bytes)
            _customAvatars.update { current ->
                if (uri == null) current else current + (profileIndex to uri)
            }
        }
    }

    fun removeCustomAvatar(profileIndex: Int) {
        scope.launch {
            CustomProfileAvatarStorage.deleteCustomAvatar(profileIndex)
            _customAvatars.update { current ->
                if (profileIndex in current) current - profileIndex else current
            }
        }
    }

    /**
     * Sweeps stored pictures whose profile no longer exists (deleted on
     * another device, or an account switch), mirroring the PIN cache
     * reconciliation in ProfileRepository: without this, an orphaned file
     * would surface as the photo of the next profile created at that index.
     */
    fun reconcileWithProfiles(existingProfileIndexes: Set<Int>) {
        scope.launch {
            val orphaned = orphanedCustomAvatarIndexes(
                storedIndexes = CustomProfileAvatarStorage.customAvatarProfileIndexes(),
                existingProfileIndexes = existingProfileIndexes,
            )
            if (orphaned.isEmpty()) return@launch
            orphaned.forEach { CustomProfileAvatarStorage.deleteCustomAvatar(it) }
            _customAvatars.update { current ->
                val kept = current.filterKeys { it !in orphaned }
                if (kept.size == current.size) current else kept
            }
        }
    }
}

/**
 * Display resolution for a profile avatar: the on-device custom picture wins,
 * then the regular avatar (custom URL, then catalog image), and null means the
 * caller should render its initial-letter fallback.
 */
internal fun resolveProfileAvatarImageUrl(
    customAvatarUri: String?,
    profile: NuvioProfile,
    avatar: AvatarCatalogItem?,
): String? =
    customAvatarUri?.takeIf { it.isNotBlank() }
        ?: profileAvatarImageUrl(profile, avatar)

@Composable
internal fun rememberCustomProfileAvatarUri(profileIndex: Int): String? {
    val customAvatars by CustomProfileAvatarRepository.customAvatars.collectAsStateWithLifecycle()
    LaunchedEffect(profileIndex) {
        CustomProfileAvatarRepository.refresh(profileIndex)
    }
    return customAvatars[profileIndex]
}
