package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object SimklPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}

internal actual object SimklPkceCrypto {
    private val secureRandom = SecureRandom()

    actual fun secureRandomBytes(size: Int): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    actual fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

internal actual object SimklAuthStorage {
    private const val METADATA_KEY = "simkl_auth_metadata"
    private const val ACCESS_TOKEN_KEY = "simkl_access_token"
    private const val CODE_VERIFIER_KEY = "simkl_code_verifier"
    private const val SECRET_KEY_KEY = "simkl_aes_key_b64"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256

    private val prefs = DesktopPreferences("simkl_auth")

    actual fun loadMetadataPayload(): String? =
        prefs.getString(ProfileScopedKey.of(METADATA_KEY))

    actual fun saveMetadataPayload(payload: String) {
        prefs.putString(ProfileScopedKey.of(METADATA_KEY), payload)
    }

    actual fun loadAccessToken(): String? = loadEncrypted(ACCESS_TOKEN_KEY)

    actual fun saveAccessToken(value: String?) = saveEncrypted(ACCESS_TOKEN_KEY, value)

    actual fun loadCodeVerifier(): String? = loadEncrypted(CODE_VERIFIER_KEY)

    actual fun saveCodeVerifier(value: String?) = saveEncrypted(CODE_VERIFIER_KEY, value)

    actual fun removeProfile(profileId: Int) {
        prefs.remove(ProfileScopedKey.of(METADATA_KEY, profileId))
        prefs.remove(ProfileScopedKey.of(ACCESS_TOKEN_KEY, profileId))
        prefs.remove(ProfileScopedKey.of(CODE_VERIFIER_KEY, profileId))
    }

    private fun loadEncrypted(key: String): String? {
        val scopedKey = ProfileScopedKey.of(key)
        val stored = prefs.getString(scopedKey) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { prefs.remove(scopedKey) }
            .getOrNull()
    }

    private fun saveEncrypted(key: String, value: String?) {
        val scopedKey = ProfileScopedKey.of(key)
        if (value.isNullOrBlank()) {
            prefs.remove(scopedKey)
        } else {
            prefs.putString(scopedKey, encrypt(value))
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return "${cipher.iv.toBase64()}.${ciphertext.toBase64()}"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex) { "Invalid encrypted credential" }
        val iv = value.substring(0, separator).fromBase64()
        val ciphertext = value.substring(separator + 1).fromBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    /**
     * Desktop has no system keystore; the AES key is persisted alongside the
     * values (obfuscation against casual inspection, not a security boundary).
     */
    private fun getOrCreateSecretKey(): SecretKey {
        prefs.getString(SECRET_KEY_KEY)?.let { stored ->
            runCatching {
                return SecretKeySpec(stored.fromBase64(), "AES")
            }
        }
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(AES_KEY_BITS, SecureRandom())
        val key = keyGenerator.generateKey()
        prefs.putString(SECRET_KEY_KEY, key.encoded.toBase64())
        return key
    }

    private fun ByteArray.toBase64(): String =
        Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray =
        Base64.getDecoder().decode(this)
}
