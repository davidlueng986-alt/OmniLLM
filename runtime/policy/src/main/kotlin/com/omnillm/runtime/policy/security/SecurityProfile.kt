package com.omnillm.runtime.policy.security

/**
 * Versioned security / crypto profile (SEC-PROFILE, specs/security-profile.yaml).
 *
 * Authority: `specs/security-profile.yaml` (`OMNILLM-SECURITY-PROFILE-2`).
 * Do not invent algorithms, sizes, TTLs, or transcript fields here.
 * Unknown major profile / algorithm id ⇒ fail closed (INV-018 / SEC-PROFILE §1).
 */
object SecurityProfile {
    const val PROFILE_ID: String = "OMNILLM-SECURITY-PROFILE-2"
    const val SCHEMA_VERSION: Int = 2
    const val STATUS: String = "BASELINE"

    // ----- Encoding ---------------------------------------------------------

    /** specs/security-profile.yaml#canonicalization.tokenEncoding */
    const val TOKEN_ENCODING: String = "base64url-without-padding"

    /** specs/security-profile.yaml#canonicalization.digestEncoding */
    const val DIGEST_ENCODING: String = "lower-case-hexadecimal"

    // ----- Algorithms -------------------------------------------------------

    const val CONTENT_DIGEST: String = "SHA-256"
    const val SIGNED_METADATA: String = "Ed25519"
    const val LAN_TLS: String = "TLS1.3"
    const val LAN_CERTIFICATE_KEY: String = "ECDSA-P-256"
    const val LAN_CERTIFICATE_SIGNATURE: String = "ECDSA-SHA-256"
    const val SPKI_FINGERPRINT: String = "SHA-256"
    const val BEARER_TOKEN_VERIFIER: String = "HMAC-SHA-256"
    const val PAIRING_PROOF: String = "HMAC-SHA-256"
    const val RECORD_ENCRYPTION: String = "AES-256-GCM"

    // ----- Token / pairing sizes (bits) -------------------------------------

    /** bearerTokenGeneration: 256-bit CSPRNG */
    const val BEARER_TOKEN_BITS: Int = 256

    /** HMAC key / AES key size for Secret Broker non-exportable keys. */
    const val SECRET_KEY_BITS: Int = 256

    /**
     * pairingSecretGeneration: 192-bit CSPRNG (spec).
     * SEC-PROFILE prose requires at least 128-bit — 192 satisfies both.
     */
    const val PAIRING_SECRET_BITS: Int = 192

    /** AES-GCM nonce: 96-bit CSPRNG unique per key. */
    const val GCM_NONCE_BITS: Int = 96

    /** AES-GCM tag bits. */
    const val GCM_TAG_BITS: Int = 128

    val BEARER_TOKEN_BYTES: Int get() = BEARER_TOKEN_BITS / 8
    val SECRET_KEY_BYTES: Int get() = SECRET_KEY_BITS / 8
    val PAIRING_SECRET_BYTES: Int get() = PAIRING_SECRET_BITS / 8
    val GCM_NONCE_BYTES: Int get() = GCM_NONCE_BITS / 8

    // ----- AIDL registration (no crypto proof; observed Binder principal) ---

    /** aidlRegistration.challengeTtlSeconds */
    const val AIDL_CHALLENGE_TTL_SECONDS: Int = 300

    // ----- LAN pairing ------------------------------------------------------

    /** lanPairing.ttlSeconds */
    const val LAN_PAIRING_TTL_SECONDS: Int = 300

    /** lanPairing.maxAttempts */
    const val LAN_PAIRING_MAX_ATTEMPTS: Int = 5

    /** lanPairing.oneTime */
    const val LAN_PAIRING_ONE_TIME: Boolean = true

    /** lanPairing.requiresLocalApproval */
    const val LAN_PAIRING_REQUIRES_LOCAL_APPROVAL: Boolean = true

    /** lanPairing.transcriptSchemaVersion */
    const val LAN_PAIRING_TRANSCRIPT_SCHEMA_VERSION: Int = 1

    /** lanPairing.protocolLabel */
    const val LAN_PAIRING_PROTOCOL_LABEL: String = "OmniLLM-LAN-Pairing-1"

    /**
     * lanPairing.transcriptFields — canonical order for HMAC-SHA-256 pairing proof.
     * Unknown field set / schema version ⇒ fail closed.
     */
    val LAN_PAIRING_TRANSCRIPT_FIELDS: List<String> = listOf(
        "protocolLabel",
        "serverSpkiSha256",
        "connectionEpoch",
        "challengeId",
        "serverNonce",
        "clientPublicKey",
        "requestedScopes",
        "issuedAt",
        "expiresAt",
    )

    // ----- Key purposes (Secret Broker) -------------------------------------

    enum class KeyPurpose {
        /** HMAC-SHA-256 key for bearer token verifiers (never leaves broker). */
        TOKEN_VERIFIER,

        /** AES-256-GCM key for pending pairing secrets / issuance receipts. */
        RECORD_ENCRYPTION,

        /** TLS server identity (LAN) — private key non-exportable. */
        LAN_TLS_IDENTITY,

        /** AI content-report draft queue encryption. */
        REPORT_QUEUE_ENCRYPTION,

        /** Catalog trust anchors (public only at app boundary). */
        CATALOG_TRUST_ANCHOR,
    }

    enum class KeyState {
        ACTIVE,
        RETIRED,
        REVOKED,
    }

    /**
     * Fail closed when a caller presents an algorithm / profile id we do not pin.
     */
    fun requireKnownProfileId(profileId: String): Boolean =
        profileId == PROFILE_ID

    fun requireKnownVerifierAlgorithm(algorithm: String): Boolean =
        algorithm == BEARER_TOKEN_VERIFIER || algorithm == "HMAC-SHA-256"

    fun requireKnownPairingProtocol(label: String): Boolean =
        label == LAN_PAIRING_PROTOCOL_LABEL
}
