package com.omnillm.features.routing.domain

import com.omnillm.core.canonical.generated.ModelRevisionId

/**
 * Alias → revision fixation at request accept (FEAT-ROUTING §1).
 *
 * Alias is resolved once when the request is accepted; subsequent planning
 * and fallback evaluation use the fixed [ModelRevisionId] only. No late
 * re-resolution that could silently change revision mid-request.
 */
object AliasResolution {

    /**
     * Resolve [aliasOrRevisionHex] against [aliasTable].
     * - If the key is an alias, returns the mapped revision.
     * - If the key is already a 64-hex revision id present as a bare id, parses it.
     * - Unknown alias / invalid hex ⇒ fail closed.
     */
    fun resolveAtAccept(
        aliasOrRevisionHex: String,
        aliasTable: Map<String, ModelRevisionId>,
    ): AliasResolveResult {
        val key = aliasOrRevisionHex.trim()
        if (key.isEmpty()) {
            return AliasResolveResult.Invalid("alias/revision must be non-empty")
        }
        aliasTable[key]?.let { return AliasResolveResult.Ok(it, resolvedFromAlias = true) }
        // Bare revision hex (catalog ModelRevisionId is 64 lowercase hex).
        return try {
            val rev = ModelRevisionId.parse(key.lowercase())
            AliasResolveResult.Ok(rev, resolvedFromAlias = false)
        } catch (e: IllegalArgumentException) {
            AliasResolveResult.Invalid(
                message = "unknown alias and invalid revision id (fail closed)",
                details = mapOf("input" to key),
            )
        }
    }
}

sealed class AliasResolveResult {
    data class Ok(
        val revisionId: ModelRevisionId,
        val resolvedFromAlias: Boolean,
    ) : AliasResolveResult()

    data class Invalid(
        val message: String,
        val details: Map<String, String> = emptyMap(),
    ) : AliasResolveResult()
}
