package com.omnillm.features.tools.domain

/**
 * Pre-engine schema admission (FEAT-TOOLS §1 / §7.1 schema bomb).
 *
 * Rejects deep, cyclic, huge enum/regex, or oversized trees **before**
 * any engine grammar compile. Pure function — no I/O.
 */
object SchemaAdmission {

    sealed class Outcome {
        data class Accepted(
            val nodeCount: Int,
            val maxDepthSeen: Int,
            val schemaBytes: Int,
            val schemaDigest: String,
        ) : Outcome()

        data class Rejected(
            val code: ValidationStatusCode,
            val reason: String,
            val details: Map<String, String> = emptyMap(),
        ) : Outcome()
    }

    fun admit(
        schema: Map<String, Any?>,
        limits: SchemaLimits = SchemaLimits.DEFAULT,
        schemaName: String? = null,
    ): Outcome {
        val started = System.nanoTime()
        schemaName?.let {
            if (it.isBlank() || it.length > limits.maxNameLength) {
                return Outcome.Rejected(
                    code = ValidationStatusCode.SCHEMA_REJECTED,
                    reason = "schema name invalid or too long",
                    details = mapOf("maxNameLength" to limits.maxNameLength.toString()),
                )
            }
        }

        val canonical = canonicalizeTree(schema)
        val bytes = canonical.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxSchemaBytes) {
            return Outcome.Rejected(
                code = ValidationStatusCode.SCHEMA_BOMB,
                reason = "schema exceeds max bytes",
                details = mapOf(
                    "schemaBytes" to bytes.toString(),
                    "maxSchemaBytes" to limits.maxSchemaBytes.toString(),
                ),
            )
        }

        val walk = WalkState(limits = limits, startedNanos = started)
        val err = walkNode(schema, depth = 0, refPath = emptyList(), state = walk)
        if (err != null) return err

        return Outcome.Accepted(
            nodeCount = walk.nodeCount,
            maxDepthSeen = walk.maxDepthSeen,
            schemaBytes = bytes,
            schemaDigest = digestSchemaTree(schema),
        )
    }

    fun admitTools(
        tools: List<ToolDefinition>,
        limits: SchemaLimits = SchemaLimits.DEFAULT,
    ): Outcome {
        if (tools.isEmpty()) {
            return Outcome.Rejected(
                code = ValidationStatusCode.SCHEMA_REJECTED,
                reason = "tools must be non-empty",
            )
        }
        if (tools.size > limits.maxToolCount) {
            return Outcome.Rejected(
                code = ValidationStatusCode.SCHEMA_BOMB,
                reason = "tool count exceeds cap",
                details = mapOf(
                    "toolCount" to tools.size.toString(),
                    "maxToolCount" to limits.maxToolCount.toString(),
                ),
            )
        }
        val ids = mutableSetOf<String>()
        for (tool in tools) {
            if (!ids.add(tool.toolId)) {
                return Outcome.Rejected(
                    code = ValidationStatusCode.SCHEMA_REJECTED,
                    reason = "duplicate toolId",
                    details = mapOf("toolId" to tool.toolId),
                )
            }
            if (tool.maxArgumentBytes > limits.maxArgumentBytes) {
                return Outcome.Rejected(
                    code = ValidationStatusCode.SCHEMA_REJECTED,
                    reason = "tool maxArgumentBytes exceeds platform cap",
                    details = mapOf(
                        "toolId" to tool.toolId,
                        "maxArgumentBytes" to tool.maxArgumentBytes.toString(),
                        "platformCap" to limits.maxArgumentBytes.toString(),
                    ),
                )
            }
            when (val r = admit(tool.parametersSchema, limits)) {
                is Outcome.Rejected -> return r.copy(
                    details = r.details + ("toolId" to tool.toolId),
                )
                is Outcome.Accepted -> Unit
            }
        }
        // Aggregate digest over ordered tool ids + schema digests.
        val aggregate = tools.sortedBy { it.toolId }
            .joinToString("\n") { "${it.toolId}:${it.schemaDigest}" }
        return Outcome.Accepted(
            nodeCount = tools.size,
            maxDepthSeen = 0,
            schemaBytes = aggregate.toByteArray(Charsets.UTF_8).size,
            schemaDigest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                "OmniLLM.ToolsSchemaDigest.v1\n$aggregate",
            ),
        )
    }

    private class WalkState(
        val limits: SchemaLimits,
        val startedNanos: Long,
        var nodeCount: Int = 0,
        var maxDepthSeen: Int = 0,
        var refExpansions: Int = 0,
    )

    private fun walkNode(
        value: Any?,
        depth: Int,
        refPath: List<String>,
        state: WalkState,
    ): Outcome.Rejected? {
        val elapsedMs = (System.nanoTime() - state.startedNanos) / 1_000_000L
        if (elapsedMs > state.limits.maxValidateWallMs) {
            return Outcome.Rejected(
                code = ValidationStatusCode.SCHEMA_BOMB,
                reason = "schema validation time cap exceeded",
                details = mapOf("maxValidateWallMs" to state.limits.maxValidateWallMs.toString()),
            )
        }
        if (depth > state.limits.maxDepth) {
            return Outcome.Rejected(
                code = ValidationStatusCode.SCHEMA_BOMB,
                reason = "schema depth exceeds cap",
                details = mapOf(
                    "depth" to depth.toString(),
                    "maxDepth" to state.limits.maxDepth.toString(),
                ),
            )
        }
        state.maxDepthSeen = maxOf(state.maxDepthSeen, depth)
        state.nodeCount += 1
        if (state.nodeCount > state.limits.maxNodes) {
            return Outcome.Rejected(
                code = ValidationStatusCode.SCHEMA_BOMB,
                reason = "schema node count exceeds cap",
                details = mapOf(
                    "nodeCount" to state.nodeCount.toString(),
                    "maxNodes" to state.limits.maxNodes.toString(),
                ),
            )
        }

        when (value) {
            null, is Boolean, is Number, is String -> return null
            is Map<*, *> -> {
                if (value.size > state.limits.maxProperties) {
                    return Outcome.Rejected(
                        code = ValidationStatusCode.SCHEMA_BOMB,
                        reason = "object property count exceeds cap",
                        details = mapOf(
                            "properties" to value.size.toString(),
                            "maxProperties" to state.limits.maxProperties.toString(),
                        ),
                    )
                }
                // Cycle via $ref path tokens (simple subset).
                val ref = value["\$ref"] as? String
                if (ref != null) {
                    if (ref in refPath) {
                        return Outcome.Rejected(
                            code = ValidationStatusCode.SCHEMA_BOMB,
                            reason = "cyclic \$ref detected",
                            details = mapOf("ref" to ref),
                        )
                    }
                    state.refExpansions += 1
                    if (state.refExpansions > state.limits.maxReferenceExpansions) {
                        return Outcome.Rejected(
                            code = ValidationStatusCode.SCHEMA_BOMB,
                            reason = "\$ref expansion count exceeds cap",
                            details = mapOf(
                                "expansions" to state.refExpansions.toString(),
                                "maxReferenceExpansions" to
                                    state.limits.maxReferenceExpansions.toString(),
                            ),
                        )
                    }
                }
                val enumVals = value["enum"]
                if (enumVals is List<*>) {
                    if (enumVals.size > state.limits.maxEnumValues) {
                        return Outcome.Rejected(
                            code = ValidationStatusCode.SCHEMA_BOMB,
                            reason = "enum size exceeds cap",
                            details = mapOf(
                                "enumSize" to enumVals.size.toString(),
                                "maxEnumValues" to state.limits.maxEnumValues.toString(),
                            ),
                        )
                    }
                    for (ev in enumVals) {
                        val s = ev?.toString().orEmpty()
                        if (s.toByteArray(Charsets.UTF_8).size > state.limits.maxEnumValueBytes) {
                            return Outcome.Rejected(
                                code = ValidationStatusCode.SCHEMA_BOMB,
                                reason = "enum value exceeds byte cap",
                                details = mapOf(
                                    "maxEnumValueBytes" to
                                        state.limits.maxEnumValueBytes.toString(),
                                ),
                            )
                        }
                    }
                }
                val pattern = value["pattern"] as? String
                if (pattern != null && pattern.length > state.limits.maxRegexLength) {
                    return Outcome.Rejected(
                        code = ValidationStatusCode.SCHEMA_BOMB,
                        reason = "regex pattern exceeds cap",
                        details = mapOf(
                            "patternLength" to pattern.length.toString(),
                            "maxRegexLength" to state.limits.maxRegexLength.toString(),
                        ),
                    )
                }
                val nextPath = if (ref != null) refPath + ref else refPath
                for ((_, child) in value) {
                    val err = walkNode(child, depth + 1, nextPath, state)
                    if (err != null) return err
                }
            }
            is List<*> -> {
                for (child in value) {
                    val err = walkNode(child, depth + 1, refPath, state)
                    if (err != null) return err
                }
            }
            is Array<*> -> {
                for (child in value) {
                    val err = walkNode(child, depth + 1, refPath, state)
                    if (err != null) return err
                }
            }
            else -> {
                // Unknown node types rejected (fail closed for schema subset).
                return Outcome.Rejected(
                    code = ValidationStatusCode.SCHEMA_REJECTED,
                    reason = "unsupported schema node type",
                    details = mapOf("type" to (value::class.simpleName ?: "unknown")),
                )
            }
        }
        return null
    }
}
