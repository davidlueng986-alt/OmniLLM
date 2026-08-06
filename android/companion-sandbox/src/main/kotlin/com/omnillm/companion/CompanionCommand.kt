package com.omnillm.companion

/**
 * Narrow companion control surface (SEC-EXTERNAL-SANDBOX §5).
 * Plan-qualified load/start/cancel/query/close only — no file/network/secret/admin RPC.
 */
sealed class CompanionCommand {
    abstract val runtimeEpoch: Long
    abstract val bootId: String
    abstract val requestId: String

    data class Handshake(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        val runtimeInstanceId: String,
        val ticket: SandboxExecutionTicket,
    ) : CompanionCommand()

    data class RegisterReadOnlyFd(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        val token: String,
        /** Mode must be READ_ONLY; write modes rejected. */
        val mode: String = CompanionFdPolicy.MODE_READ_ONLY,
    ) : CompanionCommand()

    data class Load(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        val commitId: String,
        val contentFdTokens: List<String>,
    ) : CompanionCommand()

    data class Cancel(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        val operationId: String,
    ) : CompanionCommand()

    data class Query(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        val commitId: String? = null,
    ) : CompanionCommand()

    data class Close(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
    ) : CompanionCommand()
}

sealed class CompanionCommandResult {
    data class Ok(
        val attributes: Map<String, String> = emptyMap(),
    ) : CompanionCommandResult()

    data class HandshakeOk(
        val report: CompanionIdentityReport,
    ) : CompanionCommandResult()

    data class Rejected(
        val errorCode: String,
        val message: String? = null,
    ) : CompanionCommandResult()
}
