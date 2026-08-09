package com.omnillm.features.contentreport.ports

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.contentreport.ContentReportModule
import com.omnillm.features.contentreport.domain.ConsentGrant
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import com.omnillm.features.contentreport.domain.ContentReportRecord
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.observability.ObservabilityFacade
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability availability for FEAT-AI-CONTENT-REPORT negotiation.
 * Unknown capability ⇒ fail closed (INV-018).
 */
interface CapabilityAvailabilityPort {
    fun resolve(capabilityId: CapabilityId): CapabilityState
}

class DefaultCapabilityAvailabilityPort(
    private val supported: Set<CapabilityId> = ContentReportModule.REQUIRED_CAPABILITIES,
) : CapabilityAvailabilityPort {
    override fun resolve(capabilityId: CapabilityId): CapabilityState =
        if (capabilityId in supported) CapabilityState.SUPPORTED else CapabilityState.UNKNOWN
}

/**
 * Durable report / grant store port — implemented by runtime control plane
 * (ADR-010 single writer).
 *
 * Production: [DurableContentReportStore] over SQLDelight content_reports tables.
 * Tests: [InMemoryContentReportStore]. Report stream ≠ telemetry.
 */
interface ContentReportStorePort {
    fun putReport(record: ContentReportRecord)

    /**
     * COR-23g: atomic claim-or-insert keyed by reportId. Returns true when this
     * caller inserted the record (sole claimant); false when another writer won
     * the race — caller must reconcile against the existing record instead of
     * last-writer-wins overwriting.
     */
    fun putReportIfAbsent(record: ContentReportRecord): Boolean

    fun getReport(reportId: String): ContentReportRecord?
    fun findByPrincipalAndIdempotency(
        principalId: String,
        idempotencyKey: String,
    ): ContentReportRecord?

    fun listByPrincipal(principalId: String): List<ContentReportRecord>
    /** Full scan for TTL / recovery sweeps (control-plane host only). */
    fun listAll(): List<ContentReportRecord>
    fun putGrant(grant: ConsentGrant)
    fun getGrant(grantId: String): ConsentGrant?
    fun listGrantsForReport(reportId: String): List<ConsentGrant>
    fun putReceipt(receipt: ContentReportReceipt)
    fun getReceipt(reportId: String): ContentReportReceipt?
}

/**
 * In-memory store for hermetic unit tests only.
 * **Not process-crash durable.** Production uses [DurableContentReportStore]
 * via [com.omnillm.data.persistence.ControlPlaneDatabase.contentReports] (ADR-010).
 */
class InMemoryContentReportStore : ContentReportStorePort {
    private val reports = ConcurrentHashMap<String, ContentReportRecord>()
    private val grants = ConcurrentHashMap<String, ConsentGrant>()
    private val receipts = ConcurrentHashMap<String, ContentReportReceipt>()
    private val idemIndex = ConcurrentHashMap<String, String>()

    override fun putReport(record: ContentReportRecord) {
        reports[record.reportId] = record
        idemIndex["${record.principalId}\u0000${record.idempotencyKey}"] = record.reportId
    }

    override fun putReportIfAbsent(record: ContentReportRecord): Boolean {
        val existing = reports.putIfAbsent(record.reportId, record)
        if (existing != null) return false
        idemIndex["${record.principalId}\u0000${record.idempotencyKey}"] = record.reportId
        return true
    }

    override fun getReport(reportId: String): ContentReportRecord? = reports[reportId]

    override fun findByPrincipalAndIdempotency(
        principalId: String,
        idempotencyKey: String,
    ): ContentReportRecord? {
        val id = idemIndex["$principalId\u0000$idempotencyKey"] ?: return null
        return reports[id]
    }

    override fun listByPrincipal(principalId: String): List<ContentReportRecord> =
        reports.values.filter { it.principalId == principalId }
            .sortedByDescending { it.updatedAtEpochMs }

    override fun listAll(): List<ContentReportRecord> =
        reports.values.sortedByDescending { it.updatedAtEpochMs }

    override fun putGrant(grant: ConsentGrant) {
        grants[grant.grantId] = grant
    }

    override fun getGrant(grantId: String): ConsentGrant? = grants[grantId]

    override fun listGrantsForReport(reportId: String): List<ConsentGrant> =
        grants.values.filter { it.reportId == reportId }

    override fun putReceipt(receipt: ContentReportReceipt) {
        receipts[receipt.reportId] = receipt
    }

    override fun getReceipt(reportId: String): ContentReportReceipt? = receipts[reportId]
}

/**
 * Remote developer reporting endpoint (Play profile).
 * Metrics must never treat report bodies as telemetry.
 */
interface ContentReportEndpointPort {
    fun isAvailable(): Boolean
    fun isNetworkAvailable(): Boolean

    /**
     * Submit frozen payload. Returns receipt on accept.
     * Failures are typed; never invents a second reportId.
     */
    suspend fun submit(
        reportId: String,
        canonicalPayloadDigest: String,
        payloadCanonicalJson: String,
    ): OmniResult<ContentReportReceipt>

    /** Query remote outcome by reportId after reply loss. */
    suspend fun queryReceipt(reportId: String): OmniResult<ContentReportReceipt?>

    /** Best-effort remote cancel request (may race with accept). */
    suspend fun requestCancel(reportId: String): OmniResult<Boolean>
}

object NoOpContentReportEndpoint : ContentReportEndpointPort {
    override fun isAvailable(): Boolean = false
    override fun isNetworkAvailable(): Boolean = false

    override suspend fun submit(
        reportId: String,
        canonicalPayloadDigest: String,
        payloadCanonicalJson: String,
    ): OmniResult<ContentReportReceipt> =
        OmniResult.err(
            OmniError.CONTENT_REPORT_UNAVAILABLE(
                message = "developer reporting endpoint not configured",
                details = mapOf("reportId" to reportId),
            ),
        )

    override suspend fun queryReceipt(reportId: String): OmniResult<ContentReportReceipt?> =
        OmniResult.ok(null)

    override suspend fun requestCancel(reportId: String): OmniResult<Boolean> =
        OmniResult.ok(false)
}

/**
 * Configurable Play reporting endpoint metadata (privacy disclosure, retention).
 */
interface ReportingEndpointConfigPort {
    fun isConfigured(): Boolean
    fun privacyDisclosureKey(): String
    fun retentionNoticeKey(): String
    fun contactKey(): String
    fun allowlistHost(): String?
}

data class StaticReportingEndpointConfig(
    private val configured: Boolean = true,
    private val host: String? = "reports.example.invalid",
    private val privacyKey: String = "content-report.privacy.disclosure",
    private val retentionKey: String = "content-report.retention.policy",
    private val contactKey: String = "content-report.contact",
) : ReportingEndpointConfigPort {
    override fun isConfigured(): Boolean = configured
    override fun privacyDisclosureKey(): String = privacyKey
    override fun retentionNoticeKey(): String = retentionKey
    override fun contactKey(): String = contactKey
    override fun allowlistHost(): String? = host
}

/**
 * Aggregated ports for the feature service (runtime host wiring).
 */
data class ContentReportFeaturePorts(
    val store: ContentReportStorePort,
    val endpoint: ContentReportEndpointPort,
    val endpointConfig: ReportingEndpointConfigPort,
    val capabilityAvailability: CapabilityAvailabilityPort,
    val jobManager: JobManager,
    val observability: ObservabilityFacade,
    val clockMs: () -> Long = { System.currentTimeMillis() },
)

/**
 * Fake endpoint for tests: controllable accept / fail / silent-loss.
 */
class FakeContentReportEndpoint(
    @Volatile var networkAvailable: Boolean = true,
    @Volatile var endpointAvailable: Boolean = true,
    @Volatile var acceptOnSubmit: Boolean = true,
    @Volatile var silentDrop: Boolean = false,
    @Volatile var retryableFail: Boolean = false,
) : ContentReportEndpointPort {
    private val accepted = ConcurrentHashMap<String, ContentReportReceipt>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()

    override fun isAvailable(): Boolean = endpointAvailable
    override fun isNetworkAvailable(): Boolean = networkAvailable

    override suspend fun submit(
        reportId: String,
        canonicalPayloadDigest: String,
        payloadCanonicalJson: String,
    ): OmniResult<ContentReportReceipt> {
        if (!endpointAvailable || !networkAvailable) {
            return OmniResult.err(
                OmniError.CONTENT_REPORT_UNAVAILABLE(message = "endpoint offline"),
            )
        }
        if (silentDrop) {
            // Reply lost: accept remotely but return transport error.
            accepted[reportId] = ContentReportReceipt(
                receiptId = "rcpt-$reportId",
                reportId = reportId,
                acceptedAt = "2026-01-01T00:00:00Z",
                statusUrl = "https://reports.example.invalid/status/$reportId",
            )
            return OmniResult.err(
                OmniError.INTERNAL(message = "reply lost after accept"),
            )
        }
        if (retryableFail) {
            return OmniResult.err(
                OmniError.CONTENT_REPORT_UNAVAILABLE(message = "transient upstream"),
            )
        }
        if (!acceptOnSubmit) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "remote rejected payload"),
            )
        }
        val receipt = ContentReportReceipt(
            receiptId = "rcpt-$reportId",
            reportId = reportId,
            acceptedAt = "2026-01-01T00:00:00Z",
            statusUrl = "https://reports.example.invalid/status/$reportId",
        )
        accepted[reportId] = receipt
        return OmniResult.ok(receipt)
    }

    override suspend fun queryReceipt(reportId: String): OmniResult<ContentReportReceipt?> =
        OmniResult.ok(accepted[reportId])

    override suspend fun requestCancel(reportId: String): OmniResult<Boolean> {
        cancelRequested += reportId
        // If already accepted, cancel is too late.
        return OmniResult.ok(!accepted.containsKey(reportId))
    }

    fun forceAccept(reportId: String) {
        accepted[reportId] = ContentReportReceipt(
            receiptId = "rcpt-$reportId",
            reportId = reportId,
            acceptedAt = "2026-01-01T00:00:00Z",
            statusUrl = "https://reports.example.invalid/status/$reportId",
        )
    }

    fun wasCancelRequested(reportId: String): Boolean = cancelRequested.contains(reportId)
}
