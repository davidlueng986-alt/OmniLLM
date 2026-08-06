package com.omnillm.android.runtimeservice.companion

/**
 * Host-side SandboxExecutionTicket fields (SEC-EXTERNAL-SANDBOX §3).
 * Mirrors companion ticket shape without depending on companion APK module.
 */
data class HostSandboxExecutionTicket(
    val protocolMajor: Int = CompanionHostConstants.PROTOCOL_MAJOR,
    val protocolMinor: Int = CompanionHostConstants.PROTOCOL_MINOR,
    val runtimeInstanceId: String,
    val runtimeEpoch: Long,
    val bootId: String,
    val operationId: String,
    val commitId: String,
    val engineBuildId: String,
    val modelContentIds: List<String>,
    val backend: String,
    val resourceEnvelopeSummary: String,
    val operatingConstraintSummary: String,
    val monotonicDeadlineMs: Long,
    val nonce: String,
    val placementClass: String = CompanionHostConstants.PLACEMENT_EXTERNAL_UID_ACCELERATED,
) {
    init {
        require(runtimeInstanceId.isNotEmpty())
        require(bootId.isNotEmpty())
        require(operationId.isNotEmpty())
        require(commitId.isNotEmpty())
        require(engineBuildId.isNotEmpty())
        require(modelContentIds.isNotEmpty())
        require(backend.isNotEmpty())
        require(nonce.isNotEmpty())
        require(placementClass == CompanionHostConstants.PLACEMENT_EXTERNAL_UID_ACCELERATED) {
            "host tickets for companion must be EXTERNAL_UID_ACCELERATED"
        }
    }
}

object HostSandboxTicketFactory {
    fun issue(
        runtimeInstanceId: String,
        runtimeEpoch: Long,
        bootId: String,
        operationId: String,
        commitId: String,
        engineBuildId: String,
        modelContentIds: List<String>,
        backend: String,
        resourceEnvelopeSummary: String,
        operatingConstraintSummary: String,
        monotonicDeadlineMs: Long,
        nonce: String,
    ): HostSandboxExecutionTicket =
        HostSandboxExecutionTicket(
            runtimeInstanceId = runtimeInstanceId,
            runtimeEpoch = runtimeEpoch,
            bootId = bootId,
            operationId = operationId,
            commitId = commitId,
            engineBuildId = engineBuildId,
            modelContentIds = modelContentIds,
            backend = backend,
            resourceEnvelopeSummary = resourceEnvelopeSummary,
            operatingConstraintSummary = operatingConstraintSummary,
            monotonicDeadlineMs = monotonicDeadlineMs,
            nonce = nonce,
        )

    /** Issue with a one-shot random nonce (replay-safe). */
    fun issueWithFreshNonce(
        runtimeInstanceId: String,
        runtimeEpoch: Long,
        bootId: String,
        operationId: String,
        commitId: String,
        engineBuildId: String,
        modelContentIds: List<String>,
        backend: String,
        resourceEnvelopeSummary: String,
        operatingConstraintSummary: String,
        monotonicDeadlineMs: Long,
    ): HostSandboxExecutionTicket =
        issue(
            runtimeInstanceId = runtimeInstanceId,
            runtimeEpoch = runtimeEpoch,
            bootId = bootId,
            operationId = operationId,
            commitId = commitId,
            engineBuildId = engineBuildId,
            modelContentIds = modelContentIds,
            backend = backend,
            resourceEnvelopeSummary = resourceEnvelopeSummary,
            operatingConstraintSummary = operatingConstraintSummary,
            monotonicDeadlineMs = monotonicDeadlineMs,
            nonce = java.util.UUID.randomUUID().toString(),
        )
}
