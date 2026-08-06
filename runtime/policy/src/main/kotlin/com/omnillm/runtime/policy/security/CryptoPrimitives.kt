package com.omnillm.runtime.policy.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Platform standard crypto helpers for Secret Broker (SEC-PROFILE).
 *
 * No home-grown encryption/signature. Constant-time compare for verifiers,
 * pairing proofs, and secret-derived digests.
 */
object CryptoPrimitives {

    private val secureRandom: SecureRandom = SecureRandom()

    // ----- CSPRNG -----------------------------------------------------------

    fun randomBytes(length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        val out = ByteArray(length)
        secureRandom.nextBytes(out)
        return out
    }

    /** 256-bit bearer token raw secret (SEC-PROFILE bearerTokenGeneration). */
    fun randomBearerTokenBytes(): ByteArray =
        randomBytes(SecurityProfile.BEARER_TOKEN_BYTES)

    /** 192-bit pairing secret (SEC-PROFILE pairingSecretGeneration). */
    fun randomPairingSecretBytes(): ByteArray =
        randomBytes(SecurityProfile.PAIRING_SECRET_BYTES)

    /** 256-bit HMAC / AES key material. */
    fun randomSecretKeyBytes(): ByteArray =
        randomBytes(SecurityProfile.SECRET_KEY_BYTES)

    /** 96-bit AES-GCM nonce. */
    fun randomGcmNonce(): ByteArray =
        randomBytes(SecurityProfile.GCM_NONCE_BYTES)

    // ----- Encoding ---------------------------------------------------------

    /** base64url without padding (SEC-PROFILE tokenEncoding). */
    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(text: String): ByteArray? =
        try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun lowerHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
            sb.append(HEX[b.toInt() and 0x0f])
        }
        return sb.toString()
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = lowerHex(sha256(bytes))

    // ----- HMAC-SHA-256 -----------------------------------------------------

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == SecurityProfile.SECRET_KEY_BYTES) {
            "HMAC key must be ${SecurityProfile.SECRET_KEY_BITS}-bit"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun hmacSha256(key: ByteArray, messageUtf8: String): ByteArray =
        hmacSha256(key, messageUtf8.toByteArray(Charsets.UTF_8))

    // ----- AES-256-GCM (recordEncryption) -----------------------------------

    /**
     * Encrypt [plaintext] with AES-256-GCM.
     * @return ciphertext including 128-bit tag (JCE default appends tag).
     */
    fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == SecurityProfile.SECRET_KEY_BYTES)
        require(nonce.size == SecurityProfile.GCM_NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(SecurityProfile.GCM_TAG_BITS, nonce),
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        require(key.size == SecurityProfile.SECRET_KEY_BYTES)
        require(nonce.size == SecurityProfile.GCM_NONCE_BYTES)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(SecurityProfile.GCM_TAG_BITS, nonce),
            )
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    // ----- Constant-time compare --------------------------------------------

    /**
     * Constant-time equality for secret-derived digests / verifiers
     * (algorithms.comparison).
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) {
            // Touch both arrays to avoid early-return size oracle on hot path.
            MessageDigest.isEqual(a, a)
            MessageDigest.isEqual(b, b)
            return false
        }
        return MessageDigest.isEqual(a, b)
    }

    fun constantTimeEqualsHex(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    /**
     * Best-effort wipe of secret material after one-time display or consumption.
     * JVM arrays are not zeroed by GC; callers must drop references.
     */
    fun wipe(bytes: ByteArray?) {
        if (bytes == null) return
        for (i in bytes.indices) bytes[i] = 0
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
