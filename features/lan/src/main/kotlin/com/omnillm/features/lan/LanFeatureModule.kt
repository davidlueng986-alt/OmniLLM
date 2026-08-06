package com.omnillm.features.lan

import com.omnillm.features.lan.api.LanAccessApi
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.usecase.LanAccessService
import com.omnillm.features.lan.viewmodel.LanAccessViewModel

/**
 * Feature Pack `:features:lan` (FEAT-LAN).
 *
 * Composes LAN_INTERFACE + HTTP_INTERFACE via control-plane ports only.
 * Does not redefine Request / Session / Trust (FEATURE-SYSTEM).
 *
 * Security defaults (SEC-AUTH-NET / SEC-PROFILE / FEAT-LAN):
 * - LAN default-off (`server.lanEnabled` = false)
 * - TLS 1.3 + channel-bound pairing; no plaintext token exchange
 * - QR carries only locator / SPKI / one-time challenge material — no long-lived secrets
 * - Auth fail-closed; loopback admin tokens never accepted on LAN listener
 * - Content report scopes are not telemetry
 * - Pinned modelRevisionId never silently falls back across revisions
 *
 * UI process talks only through Admin binder → these ports (INV-001 / ADR-010).
 */
object LanFeatureModule {
    const val MODULE_PATH: String = ":features:lan"
    const val FEATURE_ID: String = "FEAT-LAN"

    /** Configuration key — authority: specs/configuration-catalog.yaml */
    const val SETTING_LAN_ENABLED: String = "server.lanEnabled"

    /** Pairing protocol label — authority: specs/security-profile.yaml */
    const val PAIRING_PROTOCOL_LABEL: String = "OmniLLM-LAN-Pairing-1"

    /** Challenge TTL seconds — authority: specs/security-profile.yaml lanPairing.ttlSeconds */
    const val PAIRING_TTL_SECONDS: Int = 300

    /** Max failed exchange attempts — authority: specs/security-profile.yaml lanPairing.maxAttempts */
    const val PAIRING_MAX_ATTEMPTS: Int = 5

    /** Pairing secret entropy bits — authority: specs/security-profile.yaml algorithms.pairingSecretGeneration */
    const val PAIRING_SECRET_BITS: Int = 192

    fun createApi(ports: LanRuntimePorts): LanAccessApi =
        LanAccessService(ports)

    fun createViewModel(api: LanAccessApi): LanAccessViewModel =
        LanAccessViewModel(api)
}
