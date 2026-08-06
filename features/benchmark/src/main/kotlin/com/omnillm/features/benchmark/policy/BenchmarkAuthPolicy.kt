package com.omnillm.features.benchmark.policy

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Auth / transport policy for FEAT-BENCHMARK (LAN + report surfaces).
 *
 * Hard rules from task + SEC / FEAT-LAN:
 * - Fail closed on missing auth / unknown principal
 * - LAN_CLIENT never gets jobs.manage by default → cannot start benchmarks
 * - Report export requires LOCAL_UI (or LOCAL_ADMIN profile with jobs + metrics detail)
 * - QR payloads must never embed long-lived secrets (tokens, verifiers, private keys)
 * - Report ≠ operational telemetry (enforced at report builder boundary)
 */
object BenchmarkAuthPolicy {

    /**
     * Principals allowed to **start** benchmark jobs.
     * LAN and APP_CLIENT profiles lack `jobs.manage` → fail closed.
     */
    fun authorizeStart(
        principal: PrincipalId,
        profile: AccessProfile? = null,
    ): OmniError? {
        val kind = PrincipalKind.fromId(principal.value)
            ?: return OmniError.FORBIDDEN(
                message = "unknown principal (fail closed)",
                details = mapOf("principalId" to principal.value),
            )

        when (kind) {
            PrincipalKind.LOCAL_UI, PrincipalKind.HTTP_LOCAL_ADMIN -> {
                // LOCAL_ADMIN wildcard / LOCAL_ADMIN_HTTP has jobs.manage.
                if (kind == PrincipalKind.LOCAL_UI &&
                    principal.value != LocalUiPrincipal.ID.value
                ) {
                    return OmniError.FORBIDDEN(
                        message = "LOCAL_UI principal mismatch",
                        details = mapOf("principalId" to principal.value),
                    )
                }
                if (!hasJobManage(profile ?: defaultProfile(kind))) {
                    return OmniError.FORBIDDEN(
                        message = "jobs.manage scope required to start benchmark",
                        details = mapOf("scope" to AccessScope.jobs_manage.id),
                    )
                }
                return null
            }
            PrincipalKind.HTTP_LAN, PrincipalKind.ANDROID_APP, PrincipalKind.HTTP_LOOPBACK,
            PrincipalKind.ISOLATED_WORKER,
            -> {
                val p = profile ?: defaultProfile(kind)
                // Explicit elevated issuance could grant jobs.manage in future;
                // catalog defaults do not — fail closed unless profile allows.
                if (!hasJobManage(p)) {
                    return OmniError.FORBIDDEN(
                        message = "benchmark start denied for transport principal",
                        details = mapOf(
                            "principalKind" to kind.name,
                            "profile" to p.id,
                            "scope" to AccessScope.jobs_manage.id,
                        ),
                    )
                }
                return null
            }
        }
    }

    /**
     * Exporting a measurement report is local-admin only.
     * Never allowed for LAN_CLIENT / APP_CLIENT / WORKER (fail closed).
     */
    fun authorizeReportExport(
        principal: PrincipalId,
        profile: AccessProfile? = null,
    ): OmniError? {
        val kind = PrincipalKind.fromId(principal.value)
            ?: return OmniError.FORBIDDEN(
                message = "unknown principal (fail closed)",
                details = mapOf("principalId" to principal.value),
            )
        return when (kind) {
            PrincipalKind.LOCAL_UI, PrincipalKind.HTTP_LOCAL_ADMIN -> null
            else -> OmniError.FORBIDDEN(
                message = "measurement report export denied for principal",
                details = mapOf(
                    "principalKind" to kind.name,
                    "profile" to (profile ?: defaultProfile(kind)).id,
                    "reason" to "report_not_for_lan_or_external",
                ),
            )
        }
    }

    /**
     * Pairing / share QR payload for result locator.
     * Must never contain long-lived secrets (FEAT-LAN §2, SEC token rules).
     *
     * Allowed: challenge id, SPKI fingerprint, expiry, connection epoch, scopes requested.
     * Forbidden: bearer tokens, verifiers, private keys, refresh secrets, HMAC keys.
     */
    fun validateQrPayload(fields: Map<String, String>): OmniError? {
        val forbiddenKeys = setOf(
            "token",
            "access_token",
            "bearer",
            "refresh_token",
            "verifier",
            "secret",
            "private_key",
            "hmac_key",
            "api_key",
            "password",
        )
        for ((key, value) in fields) {
            val normalized = key.lowercase().replace('-', '_')
            if (normalized in forbiddenKeys || forbiddenKeys.any { normalized.contains(it) }) {
                return OmniError.FORBIDDEN(
                    message = "QR payload must not carry long-lived secrets",
                    details = mapOf("field" to key, "reason" to "no_long_lived_secrets_in_qr"),
                )
            }
            // Heuristic: opaque 32+ byte hex secrets that look like tokens.
            if (value.length >= 64 && value.matches(Regex("^[0-9a-fA-F]+$")) &&
                (normalized.contains("token") || normalized.contains("secret"))
            ) {
                return OmniError.FORBIDDEN(
                    message = "QR payload must not carry long-lived secrets",
                    details = mapOf("field" to key, "reason" to "no_long_lived_secrets_in_qr"),
                )
            }
        }
        return null
    }

    /**
     * Build a safe QR field map for a report share locator (no secrets).
     */
    fun safeReportQrFields(
        reportId: String,
        serverSpkiSha256: String?,
        expiresAtEpochMs: Long,
        connectionEpoch: Long,
    ): Map<String, String> {
        val fields = linkedMapOf(
            "kind" to "measurement_report_locator",
            "reportId" to reportId,
            "expiresAt" to expiresAtEpochMs.toString(),
            "connectionEpoch" to connectionEpoch.toString(),
        )
        if (serverSpkiSha256 != null) {
            fields["serverSpkiSha256"] = serverSpkiSha256
        }
        // Self-check — fail closed if we accidentally put a secret field.
        validateQrPayload(fields)?.let { err ->
            error("safeReportQrFields produced forbidden payload: ${err.message}")
        }
        return fields
    }

    private fun hasJobManage(profile: AccessProfile): Boolean =
        AccessControlCatalog.profileAllowsScope(profile, AccessScope.jobs_manage)

    private fun defaultProfile(kind: PrincipalKind): AccessProfile =
        when (kind) {
            PrincipalKind.LOCAL_UI -> AccessProfile.LOCAL_ADMIN
            PrincipalKind.HTTP_LOCAL_ADMIN -> AccessProfile.LOCAL_ADMIN_HTTP
            PrincipalKind.ANDROID_APP -> AccessProfile.APP_CLIENT
            PrincipalKind.HTTP_LOOPBACK -> AccessProfile.DEVELOPER_CLIENT
            PrincipalKind.HTTP_LAN -> AccessProfile.LAN_CLIENT
            PrincipalKind.ISOLATED_WORKER -> AccessProfile.WORKER
        }
}
