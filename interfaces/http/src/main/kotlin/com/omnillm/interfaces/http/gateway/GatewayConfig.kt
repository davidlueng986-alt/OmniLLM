package com.omnillm.interfaces.http.gateway

/**
 * Loopback HTTP gateway bind config (OpenAPI servers[0], SEC-PROFILE).
 * Cleartext only on literal loopback; token still required except minimal health.
 */
data class GatewayConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    /** Max request body bytes for JSON routes (TRANSPORT_TOO_LARGE when exceeded). */
    val maxJsonBodyBytes: Long = 4L * 1024L * 1024L,
    /** Max asset upload body (handler still enforces per-handle max_bytes). */
    val maxAssetUploadBytes: Long = 256L * 1024L * 1024L,
) {
    init {
        require(port in 1..65535) { "port out of range" }
        require(host == "127.0.0.1" || host == "::1" || host == "localhost") {
            "loopback gateway must bind literal loopback (got $host)"
        }
        require(maxJsonBodyBytes > 0) { "maxJsonBodyBytes must be positive" }
        require(maxAssetUploadBytes > 0) { "maxAssetUploadBytes must be positive" }
    }

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"
        /** OpenAPI default port. */
        const val DEFAULT_PORT: Int = 11434
    }
}
