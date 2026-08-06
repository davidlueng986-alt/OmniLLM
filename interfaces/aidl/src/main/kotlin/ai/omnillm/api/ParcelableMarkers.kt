package ai.omnillm.api

/**
 * Kotlin **parcelable markers** mirroring `specs/aidl/omnillm-aidl.yaml` field shapes.
 *
 * These are compile-time documentation / claim-shape helpers for pure-JVM tests and
 * transport adapters. Runtime Binder surfaces are the generated `.aidl` stubs in
 * `src/main/aidl/ai/omnillm/api/`. Field names match AIDL camelCase (not OpenAPI snake_case).
 *
 * Do not treat these as a second wire authority — regenerate from YAML when fields change.
 */

/** AIDL `OmniCommandRequest` — durable mutation claim (ADR-004/005). */
data class OmniCommandRequestMarker(
    val commandId: String,
    val idempotencyKey: String,
    val hasExpectedVersion: Boolean,
    val expectedVersion: Long,
    val canonicalInputDigest: String,
)

/** AIDL `CommandResult` — claim-or-return / queryCommand surface. */
data class CommandResultMarker(
    val commandId: String,
    val state: String,
    val resourceVersion: Long,
    val affectedResourceId: String? = null,
    val resultSchemaId: String? = null,
    val resultCanonicalJson: String? = null,
)

/** AIDL `OmniChatRequest` claim identity fields. */
data class OmniChatRequestClaimMarker(
    val requestId: String,
    val idempotencyKey: String,
    val model: String,
    val stream: Boolean,
)

/** AIDL `OmniEmbeddingRequest` claim identity fields. */
data class OmniEmbeddingRequestClaimMarker(
    val requestId: String,
    val idempotencyKey: String,
    val model: String,
)

/** AIDL `OmniRequestState` — queryRequest surface after reply loss. */
data class OmniRequestStateMarker(
    val requestId: String,
    val state: String,
    val resourceVersion: Long,
    val actualModelRevisionId: String? = null,
    val engineBuildId: String? = null,
    val backend: String? = null,
)

/**
 * Structural checks for AIDL claim shape (same semantic rules as HTTP CommandRequest /
 * async request claims; transport field casing differs).
 */
object AidlClaimShape {
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val UUID =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValidCommandRequest(marker: OmniCommandRequestMarker): Boolean {
        if (marker.commandId.isBlank() || !UUID.matches(marker.commandId)) return false
        if (marker.idempotencyKey.isBlank() || marker.idempotencyKey.length > 128) return false
        if (!SHA256_HEX.matches(marker.canonicalInputDigest)) return false
        if (marker.hasExpectedVersion && marker.expectedVersion < 0) return false
        return true
    }

    fun isValidChatClaim(marker: OmniChatRequestClaimMarker): Boolean {
        if (marker.requestId.isBlank() || !UUID.matches(marker.requestId)) return false
        if (marker.idempotencyKey.isBlank() || marker.idempotencyKey.length > 128) return false
        if (marker.model.isBlank()) return false
        return true
    }

    fun isValidEmbeddingClaim(marker: OmniEmbeddingRequestClaimMarker): Boolean {
        if (marker.requestId.isBlank() || !UUID.matches(marker.requestId)) return false
        if (marker.idempotencyKey.isBlank() || marker.idempotencyKey.length > 128) return false
        if (marker.model.isBlank()) return false
        return true
    }
}
