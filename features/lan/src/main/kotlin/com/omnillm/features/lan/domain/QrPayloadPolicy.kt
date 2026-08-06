package com.omnillm.features.lan.domain

import com.omnillm.features.lan.LanFeatureModule

/**
 * QR / short-code payload policy (FEAT-LAN §2, SEC-AUTH-NET §4).
 *
 * Allowed in QR: server locator, SPKI fingerprint, connection epoch,
 * protocol label, challenge id, expiry, and the **one-time short-TTL**
 * pairing secret transferred out-of-band.
 *
 * Forbidden: long-lived bearer tokens, Keystore private material, HMAC
 * verifier keys, loopback admin tokens, permanent client secrets.
 */
object QrPayloadPolicy {

    /** Fields allowed inside a QR payload (canonical names). */
    val ALLOWED_FIELDS: Set<String> = setOf(
        "protocol_label",
        "server_locator",
        "server_spki_sha256",
        "connection_epoch",
        "challenge_id",
        "pairing_secret",
        "expires_at",
        "requested_scopes",
    )

    /**
     * Fields that must never appear in a QR (long-lived or privileged secrets).
     * Pairing secret is short-TTL one-time and is **not** in this set.
     */
    val FORBIDDEN_LONG_LIVED_FIELDS: Set<String> = setOf(
        "token",
        "bearer",
        "bearer_token",
        "access_token",
        "refresh_token",
        "token_plaintext",
        "admin_token",
        "loopback_token",
        "private_key",
        "tls_private_key",
        "hmac_key",
        "verifier_key",
        "keystore_alias",
        "client_secret",
        "long_lived_secret",
    )

    data class QrMaterial(
        val protocolLabel: String,
        val serverLocator: String,
        val serverSpkiSha256: String,
        val connectionEpoch: Long,
        val challengeId: String,
        /** One-time 192-bit secret; short TTL only — not a long-lived credential. */
        val pairingSecret: String,
        val expiresAtEpochMs: Long,
        val requestedScopes: Set<String>,
    )

    fun buildPayloadMap(material: QrMaterial): Map<String, String> {
        require(material.protocolLabel == LanFeatureModule.PAIRING_PROTOCOL_LABEL) {
            "protocol_label must be ${LanFeatureModule.PAIRING_PROTOCOL_LABEL}"
        }
        require(material.serverSpkiSha256.matches(HEX64)) {
            "server_spki_sha256 must be 64-char lowercase hex"
        }
        require(material.connectionEpoch >= 0L) { "connection_epoch must be non-negative" }
        require(material.challengeId.isNotBlank()) { "challenge_id must be non-blank" }
        require(material.pairingSecret.isNotBlank()) { "pairing_secret must be non-blank" }
        require(material.serverLocator.isNotBlank()) { "server_locator must be non-blank" }
        require(material.expiresAtEpochMs > 0L) { "expires_at must be positive" }
        require(material.requestedScopes.isNotEmpty()) { "requested_scopes must be non-empty" }

        return linkedMapOf(
            "protocol_label" to material.protocolLabel,
            "server_locator" to material.serverLocator,
            "server_spki_sha256" to material.serverSpkiSha256,
            "connection_epoch" to material.connectionEpoch.toString(),
            "challenge_id" to material.challengeId,
            "pairing_secret" to material.pairingSecret,
            "expires_at" to material.expiresAtEpochMs.toString(),
            "requested_scopes" to material.requestedScopes.sorted().joinToString(","),
        )
    }

    /**
     * Encode a compact QR string. Does not embed long-lived secrets.
     * Format: `omnillm-lan-pairing-1|k=v|...` (stable for tests; runtime may re-encode).
     */
    fun encode(material: QrMaterial): String {
        val map = buildPayloadMap(material)
        return map.entries.joinToString(separator = "|", prefix = "omnillm-lan-pairing-1|") {
            "${it.key}=${it.value}"
        }
    }

    /**
     * Validate an arbitrary key/value payload (e.g. after parse).
     * Fail closed on unknown forbidden long-lived fields or missing required keys.
     */
    fun validatePayloadKeys(keys: Collection<String>): QrValidation {
        val present = keys.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val longLived = present.filter { it in FORBIDDEN_LONG_LIVED_FIELDS || looksLikeLongLivedTokenKey(it) }
        if (longLived.isNotEmpty()) {
            return QrValidation.Rejected(
                reason = "QR must not contain long-lived secrets",
                forbiddenFields = longLived,
            )
        }
        val unknown = present.filter { it !in ALLOWED_FIELDS }
        if (unknown.isNotEmpty()) {
            return QrValidation.Rejected(
                reason = "unknown QR field(s) (fail closed)",
                forbiddenFields = unknown,
            )
        }
        val missing = REQUIRED_FIELDS - present
        if (missing.isNotEmpty()) {
            return QrValidation.Rejected(
                reason = "QR missing required field(s)",
                forbiddenFields = missing.toList(),
            )
        }
        return QrValidation.Accepted
    }

    /**
     * True when [pairingSecret] is short-TTL material suitable for QR transfer
     * (non-blank, base64url-ish, not looking like a 256-bit bearer blob labeled as token).
     */
    fun isOneTimePairingSecretShape(pairingSecret: String): Boolean {
        if (pairingSecret.isBlank()) return false
        // base64url without padding, min length for ~192 bits (~32 chars) .
        if (!pairingSecret.matches(BASE64URL)) return false
        return pairingSecret.length in 32..64
    }

    private fun looksLikeLongLivedTokenKey(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("refresh") ||
            (k.contains("token") && !k.contains("pairing")) ||
            k.endsWith("_key") && k != "client_public_key"
    }

    private val REQUIRED_FIELDS: Set<String> = setOf(
        "protocol_label",
        "server_locator",
        "server_spki_sha256",
        "connection_epoch",
        "challenge_id",
        "pairing_secret",
        "expires_at",
    )

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
}

sealed class QrValidation {
    data object Accepted : QrValidation()
    data class Rejected(
        val reason: String,
        val forbiddenFields: List<String> = emptyList(),
    ) : QrValidation()
}
