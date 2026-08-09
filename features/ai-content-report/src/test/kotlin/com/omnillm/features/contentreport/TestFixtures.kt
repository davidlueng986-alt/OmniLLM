package com.omnillm.features.contentreport

import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.api.CreateProposalSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.ports.ContentReportEndpointPort
import com.omnillm.features.contentreport.ports.ContentReportFeaturePorts
import com.omnillm.features.contentreport.ports.ContentReportStorePort
import com.omnillm.features.contentreport.ports.DefaultCapabilityAvailabilityPort
import com.omnillm.features.contentreport.ports.FakeContentReportEndpoint
import com.omnillm.features.contentreport.ports.InMemoryContentReportStore
import com.omnillm.features.contentreport.ports.StaticReportingEndpointConfig
import com.omnillm.features.contentreport.usecase.ContentReportService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import java.util.concurrent.atomic.AtomicLong

fun principal(): PrincipalId = LocalUiPrincipal.ID

fun externalPrincipal(name: String = "ANDROID_APP"): PrincipalId =
    PrincipalId.parse(name)

fun cmd(suffix: String = "1"): ContentReportCommandIdentity =
    ContentReportCommandIdentity(
        commandId = uuid("c$suffix"),
        idempotencyKey = "idem-cr-$suffix",
    )

fun uuid(seed: String = "a"): String {
    val h = seed.hashCode().toUInt().toString(16).padStart(8, '0')
    return "$h-aaaa-bbbb-cccc-${h.padStart(12, '0').take(12)}"
}

/** Lower-case hex-only digest (0-9a-f). Non-hex seeds are mapped to 'a'. */
fun digest(c: Char = 'a'): String {
    val hexChar = if (c in '0'..'9' || c in 'a'..'f') c else 'a'
    return hexChar.toString().repeat(64)
}

fun proposalSpec(
    reportId: String = uuid("r1"),
    category: String = "HATE_HARASSMENT",
    command: ContentReportCommandIdentity = cmd("1"),
    modelRevisionId: String = digest('1'),
    engineBuildId: String = "llama-cpp@test",
    outputDigest: String = digest('d'),
    userConfirmed: Boolean = false,
    promptExcerpt: String? = null,
    outputExcerpt: String? = null,
    description: String? = null,
): CreateProposalSpec =
    CreateProposalSpec(
        command = command,
        reportId = reportId,
        category = category,
        createdAt = "2026-01-01T12:00:00Z",
        appBuild = "1.0.0-test",
        modelRevisionId = modelRevisionId,
        engineBuildId = engineBuildId,
        backend = "llama-cpp",
        localPolicyVersion = "policy-v1",
        outputDigest = outputDigest,
        userLocale = "en-US",
        description = description,
        promptExcerpt = promptExcerpt,
        outputExcerpt = outputExcerpt,
        userConfirmed = userConfirmed,
    )

class ClockControl(start: Long = 1_700_000_000_000L) {
    private val now = AtomicLong(start)
    fun ms(): Long = now.get()
    fun advance(deltaMs: Long) {
        now.addAndGet(deltaMs)
    }

    fun set(value: Long) {
        now.set(value)
    }
}

fun ports(
    store: ContentReportStorePort = InMemoryContentReportStore(),
    endpoint: ContentReportEndpointPort = FakeContentReportEndpoint(),
    clock: ClockControl = ClockControl(),
    endpointConfigured: Boolean = true,
): ContentReportFeaturePorts =
    ContentReportFeaturePorts(
        store = store,
        endpoint = endpoint,
        endpointConfig = StaticReportingEndpointConfig(configured = endpointConfigured),
        capabilityAvailability = DefaultCapabilityAvailabilityPort(),
        jobManager = JobManagerModule.createManager(),
        observability = ObservabilityModule.createFacade(clockWallMs = { clock.ms() }),
        clockMs = { clock.ms() },
    )

fun service(ports: ContentReportFeaturePorts = ports()): ContentReportApi =
    ContentReportService(ports)

suspend fun seedDraft(
    api: ContentReportApi,
    spec: CreateProposalSpec = proposalSpec(),
    surface: CallerSurface = CallerSurface.LOCAL_TRUSTED_UI,
    principal: PrincipalId = principal(),
    profile: String = "LOCAL_ADMIN",
) = api.createProposal(
    principal = principal,
    surface = surface,
    profileAuthenticated = true,
    accessProfileId = profile,
    spec = spec,
)

suspend fun reviewAndGrant(
    api: ContentReportApi,
    reportId: String,
    digestHex: String,
    principal: PrincipalId = principal(),
    surface: CallerSurface = CallerSurface.LOCAL_TRUSTED_UI,
    profileId: String = "local-user-1",
    warningPolicy: String = "warn-v1",
) = api.grantConsent(
    principal = principal,
    surface = surface,
    spec = GrantConsentSpec(
        reportId = reportId,
        command = cmd("grant"),
        canonicalPayloadDigest = digestHex,
        warningPolicyVersion = warningPolicy,
        localUserProfileId = profileId,
    ),
)
