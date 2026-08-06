package com.omnillm.features.tools.domain

/**
 * Post-generation JSON Schema subset validation (FEAT-TOOLS §2 POST_VALIDATE / REPAIR_RETRY).
 *
 * Pure functions — no I/O. Validates instance trees against the same schema maps
 * admitted by [SchemaAdmission]. Does **not** invent engine-native grammar results.
 *
 * Supported keywords (subset): `type`, `properties`, `required`, `items`, `enum`,
 * `minLength`/`maxLength`, `minimum`/`maximum`, `additionalProperties` (bool only).
 */
object StructuredOutputValidator {

    sealed class Outcome {
        data object Valid : Outcome()
        data class Invalid(
            val code: ValidationStatusCode,
            val reason: String,
            val path: String = "$",
            val details: Map<String, String> = emptyMap(),
        ) : Outcome()
    }

    /**
     * Validate UTF-8 JSON text against [schema].
     * Byte / node caps reuse [SchemaLimits] (fail closed before deep walk).
     */
    fun validateJson(
        json: String,
        schema: Map<String, Any?>,
        limits: SchemaLimits = SchemaLimits.DEFAULT,
    ): Outcome {
        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxOutputBytes) {
            return Outcome.Invalid(
                code = ValidationStatusCode.OUTPUT_INVALID,
                reason = "structured output exceeds maxOutputBytes",
                details = mapOf(
                    "outputBytes" to bytes.toString(),
                    "maxOutputBytes" to limits.maxOutputBytes.toString(),
                ),
            )
        }
        val instance = try {
            MinimalJson.parse(json)
        } catch (e: Exception) {
            // MinimalJson uses error() → IllegalStateException; also tolerate parse IAEs.
            return Outcome.Invalid(
                code = ValidationStatusCode.OUTPUT_INVALID,
                reason = "structured output is not valid JSON: ${e.message}",
            )
        }
        return validateInstance(instance, schema, path = "$", limits = limits, nodes = intArrayOf(0))
    }

    fun validateInstance(
        instance: Any?,
        schema: Map<String, Any?>,
        path: String = "$",
        limits: SchemaLimits = SchemaLimits.DEFAULT,
        nodes: IntArray = intArrayOf(0),
    ): Outcome {
        nodes[0]++
        if (nodes[0] > limits.maxNodes) {
            return Outcome.Invalid(
                code = ValidationStatusCode.OUTPUT_INVALID,
                reason = "instance node count exceeds cap",
                path = path,
                details = mapOf("maxNodes" to limits.maxNodes.toString()),
            )
        }

        val typeName = schema["type"] as? String
        if (typeName != null) {
            val ok = when (typeName) {
                "object" -> instance is Map<*, *>
                "array" -> instance is List<*>
                "string" -> instance is String
                "integer" -> instance is Long || instance is Int
                "number" -> instance is Number
                "boolean" -> instance is Boolean
                "null" -> instance == null
                else -> true // unknown type keyword — skip strict type (schema admission already filtered bombs)
            }
            if (!ok) {
                return Outcome.Invalid(
                    code = ValidationStatusCode.OUTPUT_INVALID,
                    reason = "type mismatch: expected $typeName",
                    path = path,
                    details = mapOf(
                        "expectedType" to typeName,
                        "actualKind" to kindOf(instance),
                    ),
                )
            }
        }

        val enumVals = schema["enum"] as? List<*>
        if (enumVals != null && instance !in enumVals) {
            return Outcome.Invalid(
                code = ValidationStatusCode.OUTPUT_INVALID,
                reason = "value not in enum",
                path = path,
            )
        }

        if (instance is String) {
            val minLen = (schema["minLength"] as? Number)?.toInt()
            val maxLen = (schema["maxLength"] as? Number)?.toInt()
            if (minLen != null && instance.length < minLen) {
                return Outcome.Invalid(
                    code = ValidationStatusCode.OUTPUT_INVALID,
                    reason = "string shorter than minLength",
                    path = path,
                )
            }
            if (maxLen != null && instance.length > maxLen) {
                return Outcome.Invalid(
                    code = ValidationStatusCode.OUTPUT_INVALID,
                    reason = "string longer than maxLength",
                    path = path,
                )
            }
        }

        if (instance is Number) {
            val min = (schema["minimum"] as? Number)?.toDouble()
            val max = (schema["maximum"] as? Number)?.toDouble()
            val v = instance.toDouble()
            if (min != null && v < min) {
                return Outcome.Invalid(
                    code = ValidationStatusCode.OUTPUT_INVALID,
                    reason = "number below minimum",
                    path = path,
                )
            }
            if (max != null && v > max) {
                return Outcome.Invalid(
                    code = ValidationStatusCode.OUTPUT_INVALID,
                    reason = "number above maximum",
                    path = path,
                )
            }
        }

        if (instance is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val obj = instance as Map<String, Any?>
            val required = (schema["required"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            for (key in required) {
                if (!obj.containsKey(key)) {
                    return Outcome.Invalid(
                        code = ValidationStatusCode.OUTPUT_INVALID,
                        reason = "missing required property",
                        path = path,
                        details = mapOf("property" to key),
                    )
                }
            }
            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as? Map<String, Any?>
            val additional = schema["additionalProperties"]
            for ((k, v) in obj) {
                val childSchema = properties?.get(k) as? Map<*, *>
                if (childSchema != null) {
                    @Suppress("UNCHECKED_CAST")
                    val child = childSchema as Map<String, Any?>
                    val childResult = validateInstance(v, child, "$path.$k", limits, nodes)
                    if (childResult is Outcome.Invalid) return childResult
                } else if (additional is Boolean && !additional) {
                    return Outcome.Invalid(
                        code = ValidationStatusCode.OUTPUT_INVALID,
                        reason = "additionalProperties not allowed",
                        path = path,
                        details = mapOf("property" to k),
                    )
                }
            }
        }

        if (instance is List<*>) {
            val items = schema["items"] as? Map<*, *>
            if (items != null) {
                @Suppress("UNCHECKED_CAST")
                val itemSchema = items as Map<String, Any?>
                instance.forEachIndexed { idx, el ->
                    val childResult = validateInstance(el, itemSchema, "$path[$idx]", limits, nodes)
                    if (childResult is Outcome.Invalid) return childResult
                }
            }
        }

        return Outcome.Valid
    }

    private fun kindOf(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> "boolean"
        is Long, is Int -> "integer"
        is Number -> "number"
        is String -> "string"
        is List<*> -> "array"
        is Map<*, *> -> "object"
        else -> value::class.simpleName ?: "unknown"
    }
}

/**
 * Minimal JSON parser for structured-output validation (no external deps).
 * Supports objects, arrays, strings, numbers, booleans, null.
 */
internal object MinimalJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.eof) error("trailing input at index ${p.i}")
        return v
    }

    private class Parser(private val s: String) {
        var i: Int = 0
        val eof: Boolean get() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (eof) error("unexpected end of JSON")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("unexpected char '${s[i]}' at $i")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = linkedMapOf<String, Any?>()
            skipWs()
            if (peek('}')) {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                out[key] = value
                skipWs()
                when {
                    peek(',') -> {
                        i++
                        continue
                    }
                    peek('}') -> {
                        i++
                        break
                    }
                    else -> error("expected ',' or '}' at $i")
                }
            }
            return out
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWs()
            if (peek(']')) {
                i++
                return out
            }
            while (true) {
                out += parseValue()
                skipWs()
                when {
                    peek(',') -> {
                        i++
                        continue
                    }
                    peek(']') -> {
                        i++
                        break
                    }
                    else -> error("expected ',' or ']' at $i")
                }
            }
            return out
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (!eof) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof) error("truncated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) error("truncated unicode escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> error("invalid escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("unterminated string")
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek('-')) i++
            if (peek('0')) {
                i++
            } else {
                if (!peekIn('1', '9')) error("invalid number at $i")
                while (peekIn('0', '9')) i++
            }
            var isFloat = false
            if (peek('.')) {
                isFloat = true
                i++
                if (!peekIn('0', '9')) error("invalid fraction")
                while (peekIn('0', '9')) i++
            }
            if (peek('e') || peek('E')) {
                isFloat = true
                i++
                if (peek('+') || peek('-')) i++
                if (!peekIn('0', '9')) error("invalid exponent")
                while (peekIn('0', '9')) i++
            }
            val raw = s.substring(start, i)
            return if (isFloat) raw.toDouble() else raw.toLong()
        }

        private fun parseLiteral(lit: String, value: Any?): Any? {
            if (i + lit.length > s.length || s.substring(i, i + lit.length) != lit) {
                error("expected $lit at $i")
            }
            i += lit.length
            return value
        }

        private fun expect(c: Char) {
            skipWs()
            if (eof || s[i] != c) error("expected '$c' at $i")
            i++
        }

        private fun peek(c: Char): Boolean = !eof && s[i] == c

        private fun peekIn(from: Char, to: Char): Boolean =
            !eof && s[i] in from..to
    }
}
