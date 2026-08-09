package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.ports.ledger.ContentReportLedgerPorts
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.ports.CapabilityAvailabilityPort
import com.omnillm.features.contentreport.ports.ContentReportEndpointPort
import com.omnillm.features.contentreport.ports.ContentReportFeaturePorts
import com.omnillm.features.contentreport.ports.ContentReportStorePort
import com.omnillm.features.contentreport.ports.DefaultCapabilityAvailabilityPort
import com.omnillm.features.contentreport.ports.DurableContentReportStore
import com.omnillm.features.contentreport.ports.InMemoryContentReportStore
import com.omnillm.features.contentreport.ports.NoOpContentReportEndpoint
import com.omnillm.features.contentreport.ports.ReportingEndpointConfigPort
import com.omnillm.features.contentreport.ports.StaticReportingEndpointConfig
import com.omnillm.features.contentreport.usecase.ContentReportService
import com.omnillm.features.contentreport.viewmodel.ContentReportViewModel
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.observability.ObservabilityFacade
import com.omnillm.runtime.policy.security.SecretBroker

/**
 * Feature pack `:features:ai-content-report` (FEAT-AI-CONTENT-REPORT).
 *
 * Composes platform capabilities from `specs/feature-capability-map.yaml`:
 * - CONTENT_REPORTING / REQUEST_LIFECYCLE / JOB_LIFECYCLE / JOB_RECOVERY
 * - LOCAL_UI_INTERFACE / USABILITY_VALIDATION / ACCESSIBILITY_VALIDATION
 *
 * Does **not** redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 * Review + ConsentGrant + submit only via non-exported LOCAL_UI (INV-001,
 * access-control: content-reports.review-submit).
 *
 * Report stream is **not** telemetry (SEC-PRIVACY). Payload is minimized and
 * owner-bound; external transports may only propose / query / cancel / discard.
 *
 * Production store: [createDurableApi] / [DurableContentReportStore] via control-plane
 * SQLite ([ContentReportLedgerPorts]) + Secret Broker [REPORT_QUEUE_ENCRYPTION] seal.
 * [InMemoryContentReportStore] is test-only (no queue encryption required).
 */
object ContentReportModule {
    const val MODULE_PATH: String = ":features:ai-content-report"
    const val FEATURE_ID: String = "FEAT-AI-CONTENT-REPORT"

    /** From `specs/feature-capability-map.yaml` for FEAT-AI-CONTENT-REPORT. */
    val REQUIRED_CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.CONTENT_REPORTING,
        CapabilityId.REQUEST_LIFECYCLE,
        CapabilityId.JOB_LIFECYCLE,
        CapabilityId.JOB_RECOVERY,
        CapabilityId.LOCAL_UI_INTERFACE,
        CapabilityId.USABILITY_VALIDATION,
        CapabilityId.ACCESSIBILITY_VALIDATION,
    )

    /**
     * Wire control-plane dependencies. Call only from runtime host
     * (`:android:runtime-service`), never from UI process.
     *
     * Prefer [createDurableApi] in production so draft/submit/reconcile survive
     * process death (ADR-010). Default [InMemoryContentReportStore] is for hermetic tests.
     */
    fun createApi(
        jobManager: JobManager,
        observability: ObservabilityFacade,
        store: ContentReportStorePort = InMemoryContentReportStore(),
        endpoint: ContentReportEndpointPort = NoOpContentReportEndpoint,
        endpointConfig: ReportingEndpointConfigPort = StaticReportingEndpointConfig(),
        capabilityAvailability: CapabilityAvailabilityPort = DefaultCapabilityAvailabilityPort(),
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): ContentReportApi =
        ContentReportService(
            ports = ContentReportFeaturePorts(
                store = store,
                endpoint = endpoint,
                endpointConfig = endpointConfig,
                capabilityAvailability = capabilityAvailability,
                jobManager = jobManager,
                observability = observability,
                clockMs = clockMs,
            ),
        )

    /**
     * Production API bound to SQLite content-report ledger + Secret Broker queue seal
     * (control-plane sole writer; FEAT-AI-CONTENT-REPORT §6).
     */
    fun createDurableApi(
        jobManager: JobManager,
        observability: ObservabilityFacade,
        ledger: ContentReportLedgerPorts,
        secretBroker: SecretBroker,
        endpoint: ContentReportEndpointPort = NoOpContentReportEndpoint,
        endpointConfig: ReportingEndpointConfigPort = StaticReportingEndpointConfig(),
        capabilityAvailability: CapabilityAvailabilityPort = DefaultCapabilityAvailabilityPort(),
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): ContentReportApi =
        createApi(
            jobManager = jobManager,
            observability = observability,
            store = DurableContentReportStore(
                ledger = ledger,
                secretBroker = secretBroker,
                clockMs = clockMs,
            ),
            endpoint = endpoint,
            endpointConfig = endpointConfig,
            capabilityAvailability = capabilityAvailability,
            clockMs = clockMs,
        )

    fun createApi(ports: ContentReportFeaturePorts): ContentReportApi =
        ContentReportService(ports = ports)

    fun createViewModel(api: ContentReportApi): ContentReportViewModel =
        ContentReportViewModel(api)
}
