package com.omnillm.features.playground.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId

/**
 * Public Playground API for LOCAL_UI composition (FEAT-PLAYGROUND).
 *
 * Composes TEXT_GENERATION / EMBEDDING / VISION_INPUT / AUDIO_INPUT /
 * REQUEST_LIFECYCLE / SESSION_LIFECYCLE / STREAMING / CANCELLATION without
 * redefining Request / Session / Trust (FEATURE-SYSTEM §1).
 *
 * Inference mutations go through runtime inference ports (AIDL / control plane);
 * this feature never writes the domain DB (ADR-010 / INV-001).
 *
 * Client generates [InferenceIdentity.requestId] and [InferenceIdentity.idempotencyKey]
 * before send; on reply loss [queryRequest] is used — never blind replay (ADR-004/005).
 */
interface PlaygroundApi {

    /** Full workspace snapshot (models, tabs, active request, metrics). */
    suspend fun getSnapshot(
        principal: PrincipalId,
        activeTab: PlaygroundTab = PlaygroundTab.CHAT,
        selectedModelRevisionId: String? = null,
    ): OmniResult<PlaygroundSnapshot>

    /** Capability negotiation for a tab + model (UNSUPPORTED vs UNKNOWN separated). */
    suspend fun negotiateCapabilities(
        principal: PrincipalId,
        tab: PlaygroundTab,
        modelRevisionId: String,
    ): OmniResult<TabCapabilityView>

    /**
     * Start Chat (plan → reserve → commit → execute via runtime).
     * First message should use [SourceSessionRef.None].
     */
    suspend fun startChat(
        principal: PrincipalId,
        spec: ChatRequestSpec,
    ): OmniResult<RequestStripUi>

    /** Start Embeddings (no generation Session). */
    suspend fun startEmbedding(
        principal: PrincipalId,
        spec: EmbeddingRequestSpec,
    ): OmniResult<RequestStripUi>

    /**
     * Multimodal Chat: assets must be READY + owner-matched before claim
     * (FEAT-PLAYGROUND §4 acceptance).
     */
    suspend fun startMultimodalChat(
        principal: PrincipalId,
        spec: MultimodalChatRequestSpec,
    ): OmniResult<RequestStripUi>

    /**
     * STRUCTURED_TOOLS tab: structured output or tool proposals via FEAT-TOOLS path.
     * Schema admission + explicit mode; never silent free-text demotion.
     */
    suspend fun startStructuredTools(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<RequestStripUi>

    /**
     * Cancel in-flight request. Surfaces cancel phase progression
     * (requested → acknowledged → execution stopped → terminal).
     */
    suspend fun cancelRequest(
        principal: PrincipalId,
        spec: CancelInferenceSpec,
    ): OmniResult<CancelStatusUi>

    /**
     * Query durable request state (reply-loss path). Never re-executes.
     */
    suspend fun queryRequest(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<RequestStripUi>

    /**
     * Stream projection for an in-flight / terminal request (FEAT-PLAYGROUND §2 / CORE-INTERFACE §4).
     *
     * Returns a bounded batch of events after [afterSeq] (exclusive). Not SSE transport —
     * UI / Admin / HTTP adapters frame delivery. Stateless Session default: no auto-continue
     * after disconnect; use [queryRequest] for durable terminal.
     */
    suspend fun streamEvents(
        principal: PrincipalId,
        requestId: String,
        afterSeq: Long = 0L,
    ): OmniResult<PlaygroundStreamBatch>
}
