package com.omnillm.engines.litertlm.sdk

import java.util.LinkedHashMap

/**
 * Bounded cooperative-cancel registry (D19).
 *
 * Mirrors the native 1024-cap + purge precedent: entries are evicted FIFO
 * once the cap is exceeded, and a completed operation [consume]s its token.
 * Memory stays bounded regardless of cancel spam or operations that never
 * run. Semantics of an evicted/consumed token: the cancel intent no longer
 * applies — a later [generate] with that token runs normally.
 */
internal class BoundedCancelRegistry(
    private val maxTokens: Int = MAX_CANCEL_TOKENS,
) {
    private val lock = Any()
    private val tokens = LinkedHashMap<String, Unit>()

    val size: Int get() = synchronized(lock) { tokens.size }

    fun add(token: String) = synchronized(lock) {
        if (tokens.containsKey(token)) return
        if (tokens.size >= maxTokens) {
            val oldest = tokens.keys.first()
            tokens.remove(oldest)
        }
        tokens[token] = Unit
    }

    fun contains(token: String): Boolean = synchronized(lock) { tokens.containsKey(token) }

    /** Consume a completed operation: its cancel intent no longer applies. */
    fun consume(token: String) = synchronized(lock) { tokens.remove(token) }

    fun clear() = synchronized(lock) { tokens.clear() }

    companion object {
        /** Native-layer precedent: the upstream runtime caps at 1024 cancels. */
        const val MAX_CANCEL_TOKENS: Int = 1024
    }
}
