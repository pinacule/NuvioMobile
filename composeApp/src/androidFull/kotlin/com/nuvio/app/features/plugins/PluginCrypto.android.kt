package com.nuvio.app.features.plugins

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val secureRandom = SecureRandom()

internal fun pluginGetRandomValues(length: Int): ByteArray {
    require(length >= 0) { "Random byte length must be non-negative" }
    val bytes = ByteArray(length)
    secureRandom.nextBytes(bytes)
    return bytes
}

internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    return MessageDigest.getInstance(normalizeDigestAlgorithm(algorithm)).digest(data)
}

internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    require(iterations > 0) { "PBKDF2 iterations must be positive" }
    require(keySizeBits > 0 && keySizeBits % 8 == 0) { "PBKDF2 key size must be a positive byte-aligned bit length" }

    val prfAlgo = normalizeHmacAlgorithm(algorithm)
    val mac = Mac.getInstance(prfAlgo)
    mac.init(SecretKeySpec(password, prfAlgo))
    
    val hLen = mac.macLength
    val dkLen = keySizeBits / 8
    val dk = ByteArray(dkLen)
    
    val blocks = (dkLen + hLen - 1) / hLen
    val u = ByteArray(hLen)
    val t = ByteArray(hLen)
    
    val blockIndexBytes = ByteArray(4)
    
    for (i in 1..blocks) {
        mac.reset()
        mac.update(salt)
        blockIndexBytes[0] = (i ushr 24).toByte()
        blockIndexBytes[1] = (i ushr 16).toByte()
        blockIndexBytes[2] = (i ushr 8).toByte()
        blockIndexBytes[3] = i.toByte()
        mac.update(blockIndexBytes)
        
        val u1 = mac.doFinal()
        u1.copyInto(t)
        u1.copyInto(u)
        
        for (j in 2..iterations) {
            mac.reset()
            val uj = mac.doFinal(u)
            uj.copyInto(u)
            for (k in 0 until hLen) {
                t[k] = (t[k].toInt() xor uj[k].toInt()).toByte()
            }
        }
        
        val offset = (i - 1) * hLen
        val len = minOf(hLen, dkLen - offset)
        t.copyInto(dk, destinationOffset = offset, startIndex = 0, endIndex = len)
    }
    
    return dk
}

internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val normalizedMode = normalizeAesTransformation(mode)
    requireValidAesKey(key)
    if (!normalizedMode.contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }
    val cipher = Cipher.getInstance(normalizedMode)
    val keySpec = SecretKeySpec(key, "AES")
    
    if (normalizedMode.contains("ECB")) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    } else if (normalizedMode.contains("GCM")) {
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    } else {
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
    }

    return cipher.doFinal(data)
}

internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    val normalizedMode = normalizeAesTransformation(mode)
    requireValidAesKey(key)
    if (!normalizedMode.contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }
    val cipher = Cipher.getInstance(normalizedMode)
    val keySpec = SecretKeySpec(key, "AES")
    
    if (normalizedMode.contains("ECB")) {
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
    } else if (normalizedMode.contains("GCM")) {
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
    } else {
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
    }

    return cipher.doFinal(data)
}

// Maps the JS shim's signature algorithm tokens to JCA names. The shim's
// __signatureAlgorithmName() always appends the hash ("ECDSA-SHA384",
// "RSASSA-PKCS1-V1_5-SHA512", defaulting to SHA256); normalizedAlgorithmToken()
// strips the dashes, so parse the trailing hash token here. MD5 is explicitly
// rejected: it is broken and must never back a signature.
private fun normalizeSignatureAlgorithm(algorithm: String): Pair<String, String> {
    val token = algorithm.normalizedAlgorithmToken()
    val (keyAlgo, hashToken) = when {
        token.startsWith("ECDSA") -> "EC" to token.removePrefix("ECDSA")
        token.startsWith("RSASSAPKCS1V15") -> "RSA" to token.removePrefix("RSASSAPKCS1V15")
        else -> error("Unsupported signature algorithm: $algorithm")
    }
    // Empty hash token = legacy callers without a hash suffix; default to SHA-256
    // exactly like the JS shim does.
    val jcaHash = when (hashToken.ifEmpty { "SHA256" }) {
        "SHA1" -> "SHA1"
        "SHA256" -> "SHA256"
        "SHA384" -> "SHA384"
        "SHA512" -> "SHA512"
        "MD5" -> error("Insecure hash MD5 is not supported for signatures: $algorithm")
        else -> error("Unsupported signature hash: $algorithm")
    }
    val jcaSigAlgo = if (keyAlgo == "EC") "ECDSA" else "RSA"
    return keyAlgo to (jcaHash + "with" + jcaSigAlgo)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    val (keyAlgo, sigAlgo) = normalizeSignatureAlgorithm(algorithm)
    val factory = KeyFactory.getInstance(keyAlgo)
    val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
    val sig = Signature.getInstance(sigAlgo)
    sig.initSign(privKey)
    sig.update(data)
    val rawSignature = sig.sign()
    // JCA emits ASN.1 DER; the JS crypto.subtle shim expects raw IEEE P1363 (r || s).
    return if (keyAlgo == "EC") ecdsaDerToP1363(rawSignature) else rawSignature
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    val (keyAlgo, sigAlgo) = normalizeSignatureAlgorithm(algorithm)
    val factory = KeyFactory.getInstance(keyAlgo)
    val pubKey = factory.generatePublic(X509EncodedKeySpec(publicKey))
    val sig = Signature.getInstance(sigAlgo)
    sig.initVerify(pubKey)
    sig.update(data)
    // WebCrypto semantics: malformed signature bytes verify as false, never throw.
    // The JS shim hands us raw IEEE P1363 (r || s); JCA expects ASN.1 DER. Tolerate
    // callers that pass DER directly (0x30 SEQUENCE marker) by normalizing through
    // P1363 first. Only bad algorithm/key (programming errors) throw.
    val derSignature = if (keyAlgo == "EC") {
        val p1363 = if (signature.isNotEmpty() && signature[0] == 0x30.toByte()) {
            runCatching { ecdsaDerToP1363(signature) }.getOrNull() ?: return false
        } else {
            if (signature.size != 2 * P256_COORD_BYTES) return false
            signature
        }
        ecdsaP1363ToDer(p1363)
    } else {
        signature
    }
    return runCatching { sig.verify(derSignature) }.getOrDefault(false)
}

// ECDSA signatures cross the native/JS boundary as raw IEEE P1363 (r || s),
// matching WebCrypto crypto.subtle. JCA's SHA256withECDSA produces/consumes ASN.1
// DER instead, so convert at the boundary. P-256 only: r and s are 32 bytes each.
private const val P256_COORD_BYTES = 32

private fun ecdsaDerToP1363(der: ByteArray): ByteArray {
    var pos = 0
    fun readByte(): Int = der[pos++].toInt() and 0xFF
    fun readLength(): Int {
        var len = readByte()
        if (len and 0x80 != 0) {
            val lenBytes = len and 0x7F
            require(lenBytes in 1..2) { "Invalid ECDSA DER length" }
            len = 0
            repeat(lenBytes) { len = (len shl 8) or readByte() }
        }
        return len
    }
    fun readInteger(): ByteArray {
        require(readByte() == 0x02) { "Invalid ECDSA DER integer tag" }
        val intLen = readLength()
        val raw = der.copyOfRange(pos, pos + intLen)
        pos += intLen
        val stripped = raw.dropWhile { it == 0.toByte() }.toByteArray()
        require(stripped.size <= P256_COORD_BYTES) { "ECDSA coordinate too large for P-256" }
        return ByteArray(P256_COORD_BYTES - stripped.size) + stripped
    }
    require(readByte() == 0x30) { "Invalid ECDSA DER sequence tag" }
    readLength()
    return readInteger() + readInteger()
}

private fun ecdsaP1363ToDer(p1363: ByteArray): ByteArray {
    require(p1363.size == 2 * P256_COORD_BYTES) { "ECDSA P-1363 signature must be 64 bytes for P-256" }
    fun encodeInteger(coord: ByteArray): ByteArray {
        val stripped = coord.dropWhile { it == 0.toByte() }.toByteArray()
            .let { if (it.isEmpty()) byteArrayOf(0) else it }
        val withSign = if (stripped[0] < 0) byteArrayOf(0) + stripped else stripped
        return byteArrayOf(0x02.toByte(), withSign.size.toByte()) + withSign
    }
    val body = encodeInteger(p1363.copyOfRange(0, P256_COORD_BYTES)) +
        encodeInteger(p1363.copyOfRange(P256_COORD_BYTES, 2 * P256_COORD_BYTES))
    return byteArrayOf(0x30.toByte(), body.size.toByte()) + body
}

internal fun pluginDigestHex(algorithm: String, data: String): String {
    val digest = pluginDigest(algorithm, data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

internal fun pluginHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val normalized = normalizeHmacAlgorithm(algorithm)
    val mac = Mac.getInstance(normalized)
    mac.init(SecretKeySpec(key, normalized))
    return mac.doFinal(data)
}

internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    val digest = pluginHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
    return digest.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

private fun normalizeDigestAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken()) {
        "MD5" -> "MD5"
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> error("Unsupported digest algorithm: $algorithm")
    }
}

private fun normalizeHmacAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "MD5" -> "HmacMD5"
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA384" -> "HmacSHA384"
        "SHA512" -> "HmacSHA512"
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }
}

private fun normalizeAesTransformation(mode: String): String {
    val normalized = mode.normalizedAlgorithmToken()
    val noPadding = normalized.contains("NOPADDING")
    val padding = if (noPadding) "NoPadding" else "PKCS5Padding"
    return when {
        normalized.contains("GCM") -> "AES/GCM/NoPadding"
        normalized.contains("ECB") -> "AES/ECB/$padding"
        normalized.contains("CBC") -> "AES/CBC/$padding"
        else -> error("Unsupported AES mode: $mode")
    }
}

private fun requireValidAesKey(key: ByteArray) {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24, or 32 bytes"
    }
}

private fun String.normalizedAlgorithmToken(): String =
    uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")
        .replace(" ", "")

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Encode(data: String): String {
    // btoa semantics: one byte per char (Latin-1); reject chars outside 0x00..0xFF.
    val bytes = ByteArray(data.length) { index ->
        val code = data[index].code
        require(code <= 0xFF) { "btoa input contains non-Latin-1 character" }
        (code and 0xFF).toByte()
    }
    return Base64.encode(bytes)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Decode(data: String): String {
    var normalized = data.trim().replace("\n", "").replace("\r", "").replace("\t", "").replace(" ", "")
    // Robust URL-safe base64 decoding fallback
    normalized = normalized.replace("-", "+").replace("_", "/")
    val padNeeded = (4 - (normalized.length % 4)) % 4
    if (padNeeded > 0) {
        normalized += "=".repeat(padNeeded)
    }
    val decoded = Base64.decode(normalized)
    // atob semantics: one char per byte (Latin-1).
    return buildString(decoded.size) {
        decoded.forEach { append(it.toUByte().toInt().toChar()) }
    }
}

internal fun pluginUtf8ToHex(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

internal fun pluginHexToByteArray(hex: String): ByteArray {
    val normalized = hex.trim().lowercase()
        .filter { !it.isWhitespace() }
        .removePrefix("0x")
    if (normalized.isEmpty()) return ByteArray(0)

    val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
    val out = ByteArray(evenHex.length / 2)
    for (index in out.indices) {
        val part = evenHex.substring(index * 2, index * 2 + 2)
        out[index] = part.toInt(16).toByte()
    }
    return out
}

internal fun pluginHexToUtf8(hex: String): String {
    return pluginHexToByteArray(hex).decodeToString()
}
