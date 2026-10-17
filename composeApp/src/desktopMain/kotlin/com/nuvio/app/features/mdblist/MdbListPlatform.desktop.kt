package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopPreferences
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM port of the Android Keystore-backed credential store.
 *
 * Desktop JVM has no system keystore, so the AES key is generated once and kept
 * in the same preferences node. This protects against casual inspection of the
 * stored values, not against a local attacker — same trade-off as any
 * file-based secret on desktop.
 */
internal actual object PlatformMdbListAuthPersistence : MdbListAuthPersistence {
    private const val keyAlias = "com.nuvio.media.mdblist.credentials.v1"
    private const val secretKeyPrefsKey = "keystore.aes_key"

    private val prefs = DesktopPreferences("mdblist_auth")
    private val lock = Any()

    actual override fun read(profileId: Int): String? = synchronized(lock) {
        val key = "profile.$profileId"
        val value = prefs.getString(key) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.remove(key)
            null
        }
    }

    actual override fun write(profileId: Int, value: String?) = synchronized(lock) {
        val key = "profile.$profileId"
        if (value == null) {
            prefs.remove(key)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.putString(key, "${encode(cipher.iv)}.${encode(encrypted)}")
        }
    }

    actual override fun clear() = synchronized(lock) {
        val keyToKeep = secretKeyPrefsKey
        val secret = prefs.getString(keyToKeep)
        prefs.clear()
        // Keep the AES key stable across clears so unrelated entries stay readable.
        if (secret != null) prefs.putString(keyToKeep, secret)
    }

    private fun secretKey(): SecretKey {
        prefs.getString(secretKeyPrefsKey)?.let { stored ->
            return SecretKeySpec(decode(stored), "AES")
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.putString(secretKeyPrefsKey, encode(key))
        return SecretKeySpec(key, "AES")
    }

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
