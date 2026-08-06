package com.omnillm.core.state

/**
 * Pure catalog guard evaluation.
 *
 * Guard strings come from `specs/state-machines.yaml` (e.g. `true`,
 * `claimMatchesCanonicalHash`, `commitKnown && !cancelPending`).
 * Named atoms are answered by a [GuardEvaluator]; the expression grammar is
 * limited to identifiers, `true`/`false`, `!`, `&&`, `||`, and parentheses.
 *
 * Runtime control plane supplies real guard facts; this module only evaluates
 * boolean expressions without I/O or domain mutation.
 */
fun interface GuardEvaluator {
    /** Whether a named guard atom is currently satisfied. */
    fun isSatisfied(guardName: String): Boolean

    companion object {
        /** Satisfies every named atom (useful for structural path tests). */
        val ALWAYS_TRUE: GuardEvaluator = GuardEvaluator { true }

        /** Rejects every named atom. */
        val ALWAYS_FALSE: GuardEvaluator = GuardEvaluator { false }

        fun of(facts: Map<String, Boolean>): GuardEvaluator =
            GuardEvaluator { name -> facts[name] == true }

        fun of(vararg facts: Pair<String, Boolean>): GuardEvaluator =
            of(facts.toMap())
    }
}

/**
 * Minimal boolean expression evaluator for catalog guard strings.
 * Unknown characters or malformed expressions fail closed as `false`.
 */
object GuardExpression {
    fun evaluate(expression: String, evaluator: GuardEvaluator): Boolean {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val parser = Parser(trimmed, evaluator)
            val value = parser.parseExpression()
            if (!parser.atEnd()) return false
            value
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private class Parser(
        private val input: String,
        private val evaluator: GuardEvaluator,
    ) {
        private var i = 0

        fun atEnd(): Boolean {
            skipWs()
            return i >= input.length
        }

        fun parseExpression(): Boolean = parseOr()

        private fun parseOr(): Boolean {
            var left = parseAnd()
            while (true) {
                skipWs()
                if (match("||")) {
                    val right = parseAnd()
                    left = left || right
                } else {
                    return left
                }
            }
        }

        private fun parseAnd(): Boolean {
            var left = parseUnary()
            while (true) {
                skipWs()
                if (match("&&")) {
                    val right = parseUnary()
                    left = left && right
                } else {
                    return left
                }
            }
        }

        private fun parseUnary(): Boolean {
            skipWs()
            if (match("!")) {
                return !parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Boolean {
            skipWs()
            if (match("(")) {
                val v = parseExpression()
                skipWs()
                if (!match(")")) throw IllegalArgumentException("expected ')'")
                return v
            }
            val ident = readIdent()
            return when (ident) {
                "true" -> true
                "false" -> false
                else -> evaluator.isSatisfied(ident)
            }
        }

        private fun readIdent(): String {
            skipWs()
            if (i >= input.length) throw IllegalArgumentException("expected identifier")
            val start = i
            val c0 = input[i]
            if (!(c0.isLetter() || c0 == '_')) {
                throw IllegalArgumentException("expected identifier at $i")
            }
            i++
            while (i < input.length) {
                val c = input[i]
                if (c.isLetterOrDigit() || c == '_') i++ else break
            }
            return input.substring(start, i)
        }

        private fun match(token: String): Boolean {
            skipWs()
            if (input.startsWith(token, i)) {
                i += token.length
                return true
            }
            return false
        }

        private fun skipWs() {
            while (i < input.length && input[i].isWhitespace()) i++
        }
    }
}
