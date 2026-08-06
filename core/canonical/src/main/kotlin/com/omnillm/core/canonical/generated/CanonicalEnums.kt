// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

/** Enum types from specs/canonical-types.yaml (kind: enum only). */

/** Catalog type `FallbackPolicy`. */
enum class FallbackPolicy {
    NONE,
    SAME_REVISION_ONLY,
    ALLOW_LIST
    ;

    companion object {
        fun fromCatalogName(name: String): FallbackPolicy? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): FallbackPolicy =
            fromCatalogName(name) ?: error("Unknown FallbackPolicy: \$name")
    }
}

/** Catalog type `CapabilityState`. */
enum class CapabilityState {
    SUPPORTED,
    UNSUPPORTED,
    CONDITIONAL,
    UNKNOWN,
    TEMPORARILY_UNAVAILABLE
    ;

    companion object {
        fun fromCatalogName(name: String): CapabilityState? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): CapabilityState =
            fromCatalogName(name) ?: error("Unknown CapabilityState: \$name")
    }
}

/** Catalog type `EvidenceLabel`. */
enum class EvidenceLabel {
    MEASURED,
    ESTIMATED,
    REPORTED,
    LAST_SAMPLED,
    UNKNOWN
    ;

    companion object {
        fun fromCatalogName(name: String): EvidenceLabel? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): EvidenceLabel =
            fromCatalogName(name) ?: error("Unknown EvidenceLabel: \$name")
    }
}

/** Catalog type `PrefixDecision`. */
enum class PrefixDecision {
    NONE,
    EXACT_SAME_SESSION,
    EXACT_CROSS_SESSION,
    TRUNCATE,
    FORK
    ;

    companion object {
        fun fromCatalogName(name: String): PrefixDecision? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): PrefixDecision =
            fromCatalogName(name) ?: error("Unknown PrefixDecision: \$name")
    }
}

/** Catalog type `ContentReportCategory`. */
enum class ContentReportCategory {
    HATE_HARASSMENT,
    SEXUAL_CONTENT,
    CHILD_SAFETY,
    VIOLENCE_SELF_HARM,
    ILLEGAL_ACTIVITY,
    DECEPTION_IMPERSONATION,
    PRIVACY_PERSONAL_DATA,
    DANGEROUS_ADVICE,
    OTHER
    ;

    companion object {
        fun fromCatalogName(name: String): ContentReportCategory? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): ContentReportCategory =
            fromCatalogName(name) ?: error("Unknown ContentReportCategory: \$name")
    }
}

/** Catalog type `ContentReportState`. */
enum class ContentReportState {
    DRAFT,
    REVIEWING,
    CONSENT_GRANTED,
    QUEUED_OFFLINE,
    SUBMITTING,
    CANCELLING,
    RECONCILING,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SUBMITTED,
    DISCARDED,
    EXPIRED
    ;

    companion object {
        fun fromCatalogName(name: String): ContentReportState? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): ContentReportState =
            fromCatalogName(name) ?: error("Unknown ContentReportState: \$name")
    }
}

/** Catalog type `QualificationStatus`. */
enum class QualificationStatus {
    NOT_EXECUTED,
    PASS,
    FAIL,
    EXPIRED,
    INVALIDATED
    ;

    companion object {
        fun fromCatalogName(name: String): QualificationStatus? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): QualificationStatus =
            fromCatalogName(name) ?: error("Unknown QualificationStatus: \$name")
    }
}

/** Catalog type `DocumentStatus`. */
enum class DocumentStatus {
    BASELINE,
    VALIDATION_REQUIRED,
    FUTURE_BASELINE,
    RETIRED
    ;

    companion object {
        fun fromCatalogName(name: String): DocumentStatus? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): DocumentStatus =
            fromCatalogName(name) ?: error("Unknown DocumentStatus: \$name")
    }
}

/** Catalog type `TokenState`. */
enum class TokenState {
    ISSUING,
    ACTIVE,
    REVOCATION_REQUESTED,
    DRAINING,
    REVOKED,
    EXPIRED,
    FAILED
    ;

    companion object {
        fun fromCatalogName(name: String): TokenState? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): TokenState =
            fromCatalogName(name) ?: error("Unknown TokenState: \$name")
    }
}
