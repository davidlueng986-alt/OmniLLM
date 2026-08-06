package com.omnillm.companion

/**
 * Read-only FD / token registry policy (SEC-EXTERNAL-SANDBOX §4).
 *
 * Host passes read-only / sealed PFDs, bounded shared memory, or pipes —
 * never path strings, DB handles, directory FDs, or general file capability.
 * Companion dups needed FDs and closes per ownership table.
 *
 * Pure policy helpers + in-memory token map for unit tests without Android PFD.
 */
object CompanionFdPolicy {
    /** Modes the companion may accept. Write / read-write are rejected. */
    const val MODE_READ_ONLY: String = "READ_ONLY"

    fun acceptMode(mode: String): Boolean = mode == MODE_READ_ONLY

    /**
     * Reject path-like or privileged capability strings (fail closed).
     * Tokens must be opaque host-issued keys, never absolute paths or content URIs
     * that would let companion open main-app private storage.
     */
    fun isOpaqueToken(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.length > 128) return false
        // Reject filesystem path shapes and content URIs (main-app private access vectors).
        if (token.startsWith("/") || token.startsWith("file:") || token.startsWith("content:")) {
            return false
        }
        if (token.contains("..") || token.contains('\\')) return false
        // Opaque alphanumeric / dash / underscore only.
        return token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }

    fun rejectReasonForToken(token: String): String? {
        if (isOpaqueToken(token)) return null
        return "fd token must be opaque; paths and content URIs are forbidden"
    }
}

/**
 * In-memory registry of adopted read-only resources keyed by opaque token.
 * [T] is typically ParcelFileDescriptor on device; tests may use Int or ByteArray.
 */
class CompanionFdRegistry<T>(
    private val closeHandle: (T) -> Unit,
) {
    private val map = LinkedHashMap<String, T>()

    fun register(token: String, handle: T): Boolean {
        if (!CompanionFdPolicy.isOpaqueToken(token)) {
            closeHandle(handle)
            return false
        }
        val previous = map.put(token, handle)
        if (previous != null) closeHandle(previous)
        return true
    }

    fun get(token: String): T? = map[token]

    fun release(token: String) {
        map.remove(token)?.let(closeHandle)
    }

    fun releaseAll() {
        val values = map.values.toList()
        map.clear()
        values.forEach(closeHandle)
    }

    val size: Int get() = map.size
}
