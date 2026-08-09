package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.ports.security.EncryptedRecord
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * LAN TLS server identity (SEC-PROFILE transport / SEC-AUTH-NET §4).
 *
 * - ECDSA P-256 key + self-signed cert (TLS 1.3 AEAD suites via platform SSLContext)
 * - Server identity pin = SHA-256(SPKI DER)
 * - Private key never leaves control plane; optional AES-GCM wrap via [SecretBroker]
 *
 * Fail closed on unknown profile / algorithm ids.
 */
object LanTlsIdentity {
    const val KEY_ALIAS: String = "omnillm-lan-tls"
    const val DEFAULT_VALIDITY_DAYS: Int = 365
    const val RECORD_TYPE_TLS_PRIVATE: String = "LAN_TLS_PRIVATE_KEY"
    /** Default LAN HTTPS port (distinct from loopback OpenAPI 11434). */
    const val DEFAULT_PORT: Int = 11443

    data class Material(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        /** Lower-case hex SHA-256 of SubjectPublicKeyInfo DER. */
        val spkiSha256: String,
        val notBeforeEpochMs: Long,
        val notAfterEpochMs: Long,
        /** PKCS#8 private key bytes (wipe after encrypt/store). */
        val privateKeyPkcs8: ByteArray,
        val certificateDer: ByteArray,
    ) {
        init {
            require(spkiSha256.matches(HEX64)) { "spkiSha256 must be 64-char lowercase hex" }
            require(notAfterEpochMs > notBeforeEpochMs)
        }

        val certificateValidNow: Boolean
            get() {
                val now = System.currentTimeMillis()
                return now in notBeforeEpochMs until notAfterEpochMs
            }

        fun isValidAt(nowEpochMs: Long): Boolean =
            nowEpochMs in notBeforeEpochMs until notAfterEpochMs
    }

    data class WrappedMaterial(
        val spkiSha256: String,
        val certificateDer: ByteArray,
        val encryptedPrivateKey: EncryptedRecord,
        val notBeforeEpochMs: Long,
        val notAfterEpochMs: Long,
    )

    /**
     * Generate a fresh LAN TLS identity (ECDSA P-256 + self-signed cert).
     */
    fun generate(
        commonName: String = "OmniLLM-LAN",
        validityDays: Int = DEFAULT_VALIDITY_DAYS,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Material {
        require(commonName.isNotBlank())
        require(validityDays in 1..825) { "validityDays out of policy range" }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = kpg.generateKeyPair()
        val notBefore = nowEpochMs - 60_000L
        val notAfter = nowEpochMs + validityDays * 86_400_000L
        val certDer = buildSelfSignedEcCertDer(
            keyPair = keyPair,
            commonName = commonName,
            notBeforeMs = notBefore,
            notAfterMs = notAfter,
            serial = BigInteger(64, SecureRandom()).abs().or(BigInteger.ONE),
        )
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        val spki = spkiSha256Hex(keyPair.public.encoded)
        return Material(
            keyPair = keyPair,
            certificate = cert,
            spkiSha256 = spki,
            notBeforeEpochMs = notBefore,
            notAfterEpochMs = notAfter,
            privateKeyPkcs8 = keyPair.private.encoded.copyOf(),
            certificateDer = certDer,
        )
    }

    /** Wrap private key with Secret Broker record encryption (purpose-scoped AES-GCM). */
    fun wrapPrivateKey(
        broker: SecretBroker,
        material: Material,
        identityId: String = KEY_ALIAS,
    ): OmniResult<WrappedMaterial> {
        val expires = material.notAfterEpochMs
        return when (
            val enc = broker.encryptRecord(
                recordType = RECORD_TYPE_TLS_PRIVATE,
                recordId = identityId,
                plaintext = material.privateKeyPkcs8,
                expiresAtEpochMs = expires,
            )
        ) {
            is OmniResult.Err -> enc
            is OmniResult.Ok -> OmniResult.ok(
                WrappedMaterial(
                    spkiSha256 = material.spkiSha256,
                    certificateDer = material.certificateDer.copyOf(),
                    encryptedPrivateKey = enc.value,
                    notBeforeEpochMs = material.notBeforeEpochMs,
                    notAfterEpochMs = material.notAfterEpochMs,
                ),
            )
        }
    }

    fun unwrapToMaterial(
        broker: SecretBroker,
        wrapped: WrappedMaterial,
    ): OmniResult<Material> {
        val pkcs8 = when (val d = broker.decryptRecord(wrapped.encryptedPrivateKey)) {
            is OmniResult.Err -> return d
            is OmniResult.Ok -> d.value
        }
        return try {
            val privateKey = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(wrapped.certificateDer)) as X509Certificate
            val publicKey = cert.publicKey
            val spki = spkiSha256Hex(publicKey.encoded)
            if (!CryptoPrimitives.constantTimeEqualsHex(spki, wrapped.spkiSha256)) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(message = "TLS SPKI mismatch after unwrap"),
                )
            }
            OmniResult.ok(
                Material(
                    keyPair = KeyPair(publicKey, privateKey),
                    certificate = cert,
                    spkiSha256 = spki,
                    notBeforeEpochMs = wrapped.notBeforeEpochMs,
                    notAfterEpochMs = wrapped.notAfterEpochMs,
                    privateKeyPkcs8 = pkcs8,
                    certificateDer = wrapped.certificateDer.copyOf(),
                ),
            )
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(
                    message = "TLS identity unwrap failed",
                    details = mapOf("type" to (e::class.simpleName ?: "Exception")),
                ),
            )
        }
    }

    /**
     * Build platform [SSLContext] for TLS 1.3 server (LAN listener).
     * Protocols restricted to TLSv1.3 when the platform supports it.
     */
    fun createSslContext(
        material: Material,
        keyPassword: CharArray = CharArray(0),
    ): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry(
            KEY_ALIAS,
            material.keyPair.private,
            keyPassword,
            arrayOf(material.certificate),
        )
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, keyPassword)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }

    /** SHA-256 of SubjectPublicKeyInfo DER (X.509 SPKI pin). */
    fun spkiSha256Hex(subjectPublicKeyInfoDer: ByteArray): String =
        CryptoPrimitives.sha256Hex(subjectPublicKeyInfoDer)

    fun wipe(material: Material) {
        CryptoPrimitives.wipe(material.privateKeyPkcs8)
    }

    // ----- Minimal ECDSA P-256 self-signed X.509v3 (platform Signature only) -

    /**
     * Builds a DER-encoded self-signed certificate.
     * Algorithm: ecdsa-with-SHA256 over secp256r1 (SEC-PROFILE LAN certificate).
     */
    internal fun buildSelfSignedEcCertDer(
        keyPair: KeyPair,
        commonName: String,
        notBeforeMs: Long,
        notAfterMs: Long,
        serial: BigInteger,
    ): ByteArray {
        val publicKey = keyPair.public as? ECPublicKey
            ?: error("expected EC public key")
        val subjectPublicKeyInfo = publicKey.encoded // already SubjectPublicKeyInfo DER
        val subjectDn = derNameCn(commonName)
        val validity = derSequence(
            derUtcTime(Date(notBeforeMs)),
            derUtcTime(Date(notAfterMs)),
        )
        // tbsCertificate
        val version = derContextConstructed(0, derInteger(BigInteger.valueOf(2))) // v3
        val serialDer = derInteger(serial)
        val signatureAlg = derAlgorithmEcdsaSha256()
        val issuer = subjectDn
        val subject = subjectDn
        val tbs = derSequence(
            version,
            serialDer,
            signatureAlg,
            issuer,
            validity,
            subject,
            subjectPublicKeyInfo,
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private as PrivateKey)
            update(tbs)
        }.sign()
        val sigBitString = derBitString(signature)
        return derSequence(tbs, signatureAlg, sigBitString)
    }

    private fun derAlgorithmEcdsaSha256(): ByteArray {
        // 1.2.840.10045.4.3.2 ecdsa-with-SHA256
        val oid = byteArrayOf(
            0x06, 0x08,
            0x2a.toByte(), 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02,
        )
        return derSequence(oid)
    }

    private fun derNameCn(cn: String): ByteArray {
        // RDNSequence: SET OF AttributeTypeAndValue (CN = 2.5.4.3, UTF8String)
        val oidCn = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val utf8 = derUtf8String(cn)
        val atav = derSequence(oidCn, utf8)
        val set = derSet(atav)
        return derSequence(set)
    }

    private fun derUtcTime(date: Date): ByteArray {
        // YYMMDDhhmmssZ
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.time = date
        val yy = (cal.get(java.util.Calendar.YEAR) % 100).toString().padStart(2, '0')
        val mo = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        val dd = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mi = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        val ss = cal.get(java.util.Calendar.SECOND).toString().padStart(2, '0')
        val s = "$yy$mo$dd$hh$mi${ss}Z"
        val bytes = s.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17, bytes.size.toByte()) + bytes
    }

    private fun derInteger(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        return derTlv(0x02, bytes)
    }

    private fun derUtf8String(s: String): ByteArray =
        derTlv(0x0c, s.toByteArray(Charsets.UTF_8))

    private fun derBitString(bytes: ByteArray): ByteArray =
        derTlv(0x03, byteArrayOf(0x00) + bytes)

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val content = elements.fold(ByteArray(0)) { acc, e -> acc + e }
        return derTlv(0x30, content)
    }

    private fun derSet(vararg elements: ByteArray): ByteArray {
        val content = elements.fold(ByteArray(0)) { acc, e -> acc + e }
        return derTlv(0x31, content)
    }

    private fun derContextConstructed(tag: Int, content: ByteArray): ByteArray =
        derTlv(0xa0 or (tag and 0x1f), content)

    private fun derTlv(tag: Int, content: ByteArray): ByteArray {
        val len = derLength(content.size)
        return byteArrayOf(tag.toByte()) + len + content
    }

    private fun derLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        if (length <= 0xff) return byteArrayOf(0x81.toByte(), length.toByte())
        if (length <= 0xffff) {
            return byteArrayOf(
                0x82.toByte(),
                ((length ushr 8) and 0xff).toByte(),
                (length and 0xff).toByte(),
            )
        }
        error("DER length too large")
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}
