package com.omnillm.engines.mllm.mapping

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError

/**
 * Validates capability-scoped / engine extension parameters for mllm.
 *
 * ENGINE-STANDARD §3: adapters must **not** silently ignore unsupported parameters.
 * Unknown keys and explicitly unsupported keys fail closed.
 */
object ParameterValidator {

    /**
     * Keys the mllm adapter understands on inference/load attribute maps.
     * Everything else is unknown unless allowlisted by [extraAllowed].
     */
    val KNOWN_ATTRIBUTE_KEYS: Set<String> = setOf(
        "backend",
        "maxTokens",
        "temperature",
        "topP",
        "topK",
        "seed",
        "stopCount",
        "placementHint",
        "modelEnvelope",
        "workloadEnvelope",
        "prefixMode",
        // Opaque digests / broker keys (not paths)
        "storageRootKey",
        "installationKey",
        "serverProtocolVersion",
        "channelKind",
        "ownerKey",
    )

    /**
     * Parameters that exist in the ecosystem but are unsupported-by-default
     * for this build until qualification (ENGINE-MLLM §9).
     */
    val UNSUPPORTED_BY_DEFAULT: Set<String> = setOf(
        "multimodalImage",
        "visionTower",
        "embeddingPooling",
        "toolSchema",
        "structuredOutput",
        "grammar",
        "speculativeDraft",
        "lanBind",
        "corsWildcard",
        "externalGatewayBind",
        "crossSessionKvReuse",
    )

    /**
     * Validate attribute map. Returns first error or null if all keys are known
     * and none are unsupported.
     */
    fun validateAttributes(
        attributes: Map<String, String>,
        extraAllowed: Set<String> = emptySet(),
        allowUnsupported: Set<String> = emptySet(),
    ): OmniError? {
        val allowed = KNOWN_ATTRIBUTE_KEYS + extraAllowed
        for ((key, _) in attributes) {
            if (key in UNSUPPORTED_BY_DEFAULT && key !in allowUnsupported) {
                return ErrorMapper.unsupportedParameter(
                    name = key,
                    reason = "unsupported-by-default until qualification cell PASS",
                )
            }
            if (key !in allowed && key !in UNSUPPORTED_BY_DEFAULT) {
                return ErrorMapper.unknownParameter(key)
            }
        }
        return null
    }

    fun <T> validateOrErr(
        attributes: Map<String, String>,
        extraAllowed: Set<String> = emptySet(),
        allowUnsupported: Set<String> = emptySet(),
    ): OmniResult<Unit> {
        val err = validateAttributes(attributes, extraAllowed, allowUnsupported)
        return if (err != null) OmniResult.err(err) else OmniResult.ok(Unit)
    }

    fun parsePositiveInt(attributes: Map<String, String>, key: String, default: Int): OmniResult<Int> {
        val raw = attributes[key] ?: return OmniResult.ok(default)
        val v = raw.toIntOrNull()
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "invalid integer for $key",
                    details = mapOf("parameter" to key),
                ),
            )
        if (v <= 0) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "$key must be positive",
                    details = mapOf("parameter" to key),
                ),
            )
        }
        return OmniResult.ok(v)
    }
}
