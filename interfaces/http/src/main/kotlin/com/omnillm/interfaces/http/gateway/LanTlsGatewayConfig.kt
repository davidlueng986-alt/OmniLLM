package com.omnillm.interfaces.http.gateway

/**
 * LAN TLS listener bind config (FEAT-LAN §1, SEC-PROFILE TLS 1.3).
 *
 * Default-off at product layer; this config is only applied after explicit enable.
 * [host] may be a LAN interface address or wildcard `0.0.0.0` / `::` when policy allows.
 * Unlike [GatewayConfig], this intentionally allows non-loopback binds.
 */
data class LanTlsGatewayConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val maxJsonBodyBytes: Long = 4L * 1024L * 1024L,
    val maxAssetUploadBytes: Long = 256L * 1024L * 1024L,
) {
    init {
        require(port in 1..65535) { "port out of range" }
        require(host.isNotBlank()) { "host must be non-blank" }
        require(maxJsonBodyBytes > 0)
        require(maxAssetUploadBytes > 0)
    }

    fun toGatewayConfig(): GatewayConfig =
        // GatewayConfig only accepts loopback hosts — LAN uses a parallel path.
        // Callers use [host]/[port] directly on the TLS engine.
        GatewayConfig(
            host = "127.0.0.1",
            port = port.coerceIn(1, 65535),
            maxJsonBodyBytes = maxJsonBodyBytes,
            maxAssetUploadBytes = maxAssetUploadBytes,
        )

    companion object {
        /** Bind all IPv4 interfaces (policy still gates enable). */
        const val DEFAULT_HOST: String = "0.0.0.0"
        const val DEFAULT_PORT: Int = 11443
    }
}
