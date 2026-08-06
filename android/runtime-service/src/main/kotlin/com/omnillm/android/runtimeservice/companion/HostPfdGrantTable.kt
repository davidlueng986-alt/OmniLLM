package com.omnillm.android.runtimeservice.companion

/**
 * Host-side read-only PFD grant table (SEC-EXTERNAL-SANDBOX §4).
 *
 * Records only opaque tokens + mode + request scope. Never stores paths,
 * DB handles, directory FDs, or general file capability strings.
 * Grants are request-scoped; cancel/close/crash paths must revoke.
 *
 * Pure in-memory policy — unit-testable without Android PFD types.
 */
class HostPfdGrantTable {

    data class Grant(
        val token: String,
        val mode: String,
        val requestId: String,
        val runtimeEpoch: Long,
        val bootId: String,
        val issuedAtMonotonicMs: Long,
    )

    sealed class GrantResult {
        data class Ok(val grant: Grant) : GrantResult()
        data class Rejected(val errorCode: String, val message: String) : GrantResult()
    }

    private val grants = LinkedHashMap<String, Grant>()

    val size: Int get() = grants.size

    fun get(token: String): Grant? = grants[token]

    fun allTokens(): Set<String> = grants.keys.toSet()

    /**
     * Record a host-issued RO grant after policy checks.
     * [mode] must be READ_ONLY; tokens must be opaque (no paths/URIs).
     */
    fun grant(
        token: String,
        mode: String = MODE_READ_ONLY,
        requestId: String,
        runtimeEpoch: Long,
        bootId: String,
        nowMonotonicMs: Long,
    ): GrantResult {
        if (mode != MODE_READ_ONLY) {
            return GrantResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "only READ_ONLY PFD grants allowed",
            )
        }
        rejectReasonForToken(token)?.let { reason ->
            return GrantResult.Rejected("INVALID_REQUEST", reason)
        }
        if (requestId.isEmpty()) {
            return GrantResult.Rejected("INVALID_REQUEST", "requestId required for grant")
        }
        if (bootId.isEmpty() || runtimeEpoch < 0L) {
            return GrantResult.Rejected("INVALID_REQUEST", "epoch/bootId required for grant")
        }
        val g = Grant(
            token = token,
            mode = mode,
            requestId = requestId,
            runtimeEpoch = runtimeEpoch,
            bootId = bootId,
            issuedAtMonotonicMs = nowMonotonicMs,
        )
        grants[token] = g
        return GrantResult.Ok(g)
    }

    fun revoke(token: String): Boolean = grants.remove(token) != null

    fun revokeAllForRequest(requestId: String): Int {
        val doomed = grants.filterValues { it.requestId == requestId }.keys.toList()
        doomed.forEach { grants.remove(it) }
        return doomed.size
    }

    fun revokeAllForEpoch(runtimeEpoch: Long, bootId: String): Int {
        val doomed = grants.filterValues {
            it.runtimeEpoch == runtimeEpoch && it.bootId == bootId
        }.keys.toList()
        doomed.forEach { grants.remove(it) }
        return doomed.size
    }

    fun releaseAll() {
        grants.clear()
    }

    companion object {
        const val MODE_READ_ONLY: String = "READ_ONLY"

        /**
         * Opaque host-issued keys only — never absolute paths or content URIs
         * that would let companion open main-app private storage.
         */
        fun isOpaqueToken(token: String): Boolean {
            if (token.isEmpty()) return false
            if (token.length > 128) return false
            if (token.startsWith("/") || token.startsWith("file:") || token.startsWith("content:")) {
                return false
            }
            if (token.contains("..") || token.contains('\\')) return false
            return token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        }

        fun rejectReasonForToken(token: String): String? {
            if (isOpaqueToken(token)) return null
            return "fd token must be opaque; paths and content URIs are forbidden"
        }
    }
}
