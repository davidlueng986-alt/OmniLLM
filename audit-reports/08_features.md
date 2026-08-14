# 08_features — Full FEAT-* Matrix Audit

| Field | Value |
|-------|--------|
| **Artifact** | `08_features.md` + `08_features.json` |
| **Audit date** | 2026-08-12 |
| **Docs package (authority)** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Spec authority** | `specs/feature-capability-map.yaml` (precedence over prose when IDs/capabilities conflict) |
| **Method** | `list_dir` / `read_file` / `grep` on real paths; fail-closed without path evidence |
| **Status labels** | `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN` only |

## Scoring rules (this pass)

| Level | Meaning | PASS requires |
|---|---|---|
| **L1** | Feature pack module exists with `FEATURE_ID`, service/API, unit tests | Path under `features/*` + `FEATURE_ID` constant + Gradle include |
| **L2** | Wired into runtime control plane | `WaveAWiring` / `FeaturePackHost.bootstrap` / `RuntimeControlPlane` exposure |
| **L3** | Product journey software-complete without device | UI surface + binder/HTTP path can drive the primary journey; import/generate may stay PARTIAL if stubbed; **no** invented device PASS / Play / OEM results |
| **Overall `status`** | Min of L1/L2/L3 (PASS > PARTIAL > MISSING) | |

**Not claimed:** engine QUALIFIED/SUPPORTED, device matrix PASS, Play upload, OEM results.

---

## Authority catalog

### Docs prose (`docs/70-features/README.md`)

| Doc ID | Design file | Monorepo module |
|---|---|---|
| `FEAT-ADMIN` | `administration-jobs.md` | `:features:admin` |
| `FEAT-AI-CONTENT-REPORT` | `ai-content-reporting.md` | `:features:ai-content-report` |
| `FEAT-AUTOSETUP` | `auto-setup.md` | `:features:auto-setup` |
| `FEAT-BENCHMARK` | `benchmark-research.md` | `:features:benchmark` |
| `FEAT-DASHBOARD` | `dashboard-monitoring.md` | `:features:dashboard` |
| `FEAT-SERVER` | `developer-server.md` | `:features:server` |
| `FEAT-DIAGNOSTICS` | `diagnostics-export.md` | `:features:diagnostics` |
| `FEAT-LAN` | `lan-access.md` | `:features:lan` |
| `FEAT-PLAYGROUND` | `local-playground.md` | `:features:playground` |
| `FEAT-MODELHUB` | `modelhub-acquisition.md` | `:features:modelhub` |
| `FEAT-ROUTING` | `multi-model-routing.md` | `:features:routing` |
| `FEAT-TOOLS` | `structured-tools.md` | `:features:tools` |
| `FEATURE-SYSTEM` | `feature-design-system.md` | N/A (design system, not a pack) |

### Specs (`specs/feature-capability-map.yaml` schemaVersion 2)

12 featureIds: `FEAT-AUTOSETUP`, `FEAT-MODELHUB`, `FEAT-PLAYGROUND`, `FEAT-SERVER`, `FEAT-LAN`, `FEAT-DASHBOARD`, `FEAT-BENCHMARK`, `FEAT-DIAGNOSTICS`, `FEAT-ROUTING`, `FEAT-TOOLS`, `FEAT-ADMIN`, **`FEAT-AI-REPORTING`**.

#### ID alias residual

| Source | AI content feature ID |
|---|---|
| Docs prose `ai-content-reporting.md` | `FEAT-AI-CONTENT-REPORT` |
| Docs package `feature-capability-map.yaml` | **`FEAT-AI-REPORTING`** (specs precedence) |
| Monorepo code `ContentReportModule.FEATURE_ID` | `FEAT-AI-CONTENT-REPORT` |
| Monorepo module path | `features/ai-content-report` |

Audit matrix uses **both** IDs: primary row keyed to monorepo/code + prose (`FEAT-AI-CONTENT-REPORT`), with alias note for YAML `FEAT-AI-REPORTING`.

### Gradle includes (`settings.gradle.kts`)

All 12 packs included: `:features:auto-setup` … `:features:ai-content-report`.

### Control-plane attach map

| Wave | Feature IDs | Host |
|---|---|---|
| **Wave-A** | ADMIN, AUTOSETUP, MODELHUB, PLAYGROUND, SERVER, DASHBOARD | `WaveAWiring.WAVE_A_FEATURE_IDS` → `WaveAFeaturePacks` |
| **Wave-B** | LAN, BENCHMARK, DIAGNOSTICS, ROUTING, TOOLS, AI-CONTENT-REPORT | `FeaturePackHost.WAVE_B_FEATURE_IDS` |

Evidence: `android/runtime-service/.../featurehost/WaveAWiring.kt`, `FeaturePackHost.kt`, `RuntimeControlPlane.kt`.

### UI attach map (`UiSession` admin listener)

**Attached on binder connect:** adminHome, adminJobs, adminSettings, modelHub, autoSetup, playground, dashboard, benchmark, server, lan.

**Declared but never attached in `adminListener`:** `diagnosticsVm`, `contentReportVm`, `routingVm` → screens always render EMPTY / disconnected unless injected externally.

Evidence: `android/app-ui/.../session/UiSession.kt` lines 76–91 vs 65–73 / 130–148.

---

## Summary matrix

| featureId | L1 | L2 | L3 | Overall | Primary residual |
|---|---|---|---|---|---|
| FEAT-AUTOSETUP | PASS | PASS | PARTIAL | **PARTIAL** | UI path fail-closes model/orchestrator ports; full first-inference journey incomplete |
| FEAT-MODELHUB | PASS | PASS | PARTIAL | **PARTIAL** | Admin projection: delete/pin/load/unload/license CAPABILITY_UNSUPPORTED |
| FEAT-PLAYGROUND | PASS | PASS | PARTIAL | **PARTIAL** | Chat exploratory path only; embed/structured fail-closed on Admin; multimodal capability blocked |
| FEAT-SERVER | PASS | PASS | PARTIAL | **PARTIAL** | Loopback ensure/token/client empty on Admin projection; HTTP plane richer |
| FEAT-LAN | PASS | PASS | PARTIAL | **PARTIAL** | UI enable/pairing fail-closed; plane+HTTP LAN host complete-ish |
| FEAT-DASHBOARD | PASS | PASS | PARTIAL | **PARTIAL** | Admin snapshot shallow (jobs-as-requests; no traces/metrics) |
| FEAT-BENCHMARK | PASS | PASS | PARTIAL | **PARTIAL** | Plan+start job wired; export/completeRun control-plane only; runs empty on Admin |
| FEAT-DIAGNOSTICS | PASS | PASS | PARTIAL | **PARTIAL** | Plane+HTTP export exist; UI VM never attached |
| FEAT-ROUTING | PASS | PASS | PARTIAL | **PARTIAL** | Orchestrator-bound on plane; UI VM never attached |
| FEAT-TOOLS | PASS | PASS | PARTIAL | **PARTIAL** | Durable ledger + ToolsApi; LOCAL_UI structured fail-closed; no dedicated tools screen |
| FEAT-ADMIN | PASS | PASS | PARTIAL | **PARTIAL** | Home/settings/jobs via binder; EmptyAdminModelPort on binder models |
| FEAT-AI-CONTENT-REPORT *(alias FEAT-AI-REPORTING)* | PASS | PASS | PARTIAL | **PARTIAL** | Plane+AIDL+HTTP; UI VM never attached |
| FEATURE-SYSTEM | N_A | N_A | N_A | **N_A** | Design system, not a product pack |

**No FEAT is L3 PASS** in this software-only audit: every journey has at least one UI→binder projection gap, capability fail-closed surface, or exploratory/UNQUALIFIED engine honesty constraint.

---

## Per-feature detail

### 1. FEAT-AUTOSETUP

| Field | Value |
|---|---|
| **featureId** | `FEAT-AUTOSETUP` |
| **Doc path** | `docs/70-features/auto-setup.md` |
| **Module path** | `features/auto-setup` → `:features:auto-setup` (`AutoSetupModule.MODULE_PATH`) |
| **FEATURE_ID** | `AutoSetupModule.FEATURE_ID = "FEAT-AUTOSETUP"` |
| **L1** | **PASS** |
| **L2** | **PASS** |
| **L3** | **PARTIAL** |
| **Overall** | **PARTIAL** |

**Capabilities (YAML):** DEVICE_DISCOVERY, MODEL_ACQUISITION, MODEL_IDENTITY, COMPATIBILITY_EVALUATION, RECOMMENDATION, AUTOMATED_CONFIGURATION, SAFE_INSTALLATION, RESOURCE_ACCOUNTING, LOCAL_UI_INTERFACE, USABILITY_VALIDATION, ACCESSIBILITY_VALIDATION

| Capability | Covered? | Evidence / gap |
|---|---|---|
| DEVICE_DISCOVERY | PARTIAL | `DeviceProbePort` + `JvmDeviceProbe` (plane) / `AndroidUiDeviceProbe` (UI — lightweight, no RAM/storage) |
| RECOMMENDATION | L1/L2 | `RecommendationRanker`, `FixtureCatalogCandidates` |
| AUTOMATED_CONFIGURATION | L1/L2 | `AutomatedConfigurationBuilder` |
| MODEL_ACQUISITION / SAFE_INSTALLATION | PARTIAL | Job port via Admin `startJob`; UI models/orchestrator **FailClosed** |
| COMPATIBILITY_EVALUATION | PARTIAL | Ranker fail-closes UNKNOWN/UNSUPPORTED; no device evidence elevation |
| LOCAL_UI_INTERFACE | PARTIAL | `OnboardingScreen` + `AutoSetupViewModel` attached |
| USABILITY / ACCESSIBILITY_VALIDATION | PARTIAL | UI chrome/a11y semantics present; no dedicated a11y test suite for journey |

**UI surface:** `OmniDestination.Onboarding` → `OnboardingScreen` (drawer); deep-link `onboarding`/`setup`.

**HTTP/AIDL:** No dedicated HTTP ops. Jobs via `IOmniAdmin.startJob` (DOWNLOAD/IMPORT). No AutoSetup-specific AIDL.

**Tests (unit, 0 failures in build results):** `AutoSetupServiceTest` (9), `RecommendationRankerTest` (7), `AutomatedConfigurationBuilderTest` (4), `AutoSetupStateProjectionTest` (6).

**Residuals:** UI wires `FailClosedAutoSetupModelPort` + `FailClosedAutoSetupOrchestratorPort` (`AdminLiveFeatureFactory`); plane Wave-A uses real JobManager/ModelManager/Orchestrator ports — product journey on device UI cannot complete planLoad/first-inference without that plane path.

---

### 2. FEAT-MODELHUB

| Field | Value |
|---|---|
| **featureId** | `FEAT-MODELHUB` |
| **Doc path** | `docs/70-features/modelhub-acquisition.md` |
| **Module path** | `features/modelhub` → `:features:modelhub` |
| **FEATURE_ID** | `ModelhubModule.FEATURE_ID = "FEAT-MODELHUB"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** MODEL_ACQUISITION, MODEL_IDENTITY, MODEL_LIFECYCLE, SAFE_INSTALLATION, COMPATIBILITY_EVALUATION, JOB_LIFECYCLE, JOB_RECOVERY, RESOURCE_ACCOUNTING, LOCAL_UI_INTERFACE

| Capability | Covered? | Notes |
|---|---|---|
| MODEL_ACQUISITION | PARTIAL→strong on plane | `AcquisitionPipeline`, `OkHttpArtifactByteSource`, fixture pin download; Admin `startDownload`/`startImport`/`importLocalFile` |
| SAFE_INSTALLATION | L2 | Quarantine → verify → READY pipeline tests |
| MODEL_LIFECYCLE | PARTIAL | Plane has loadRuntime/lifecycle ports; Admin projection returns CAPABILITY_UNSUPPORTED for load/unload/pin/delete/license |
| JOB_LIFECYCLE / RECOVERY | L2 | JobManager DOWNLOAD/IMPORT; cancel via Admin |
| LOCAL_UI_INTERFACE | PARTIAL | `ModelHubScreen` primary rail; local SAF importer |

**UI:** Primary bottom bar `ModelHub` / detail deep-link.

**HTTP/AIDL:** `IOmniAdmin.startJob`, `importLocalFile`, snapshot models; HTTP `/v1/models` lists models (server surface).

**Tests:** `ModelHubServiceTest` (19), `AcquisitionPipelineTest` (7), `OkHttpArtifactByteSourceTest` (12); runtime `OmniModelPageProjectionTest`.

**Residuals:** Admin-projected API gaps (delete/pin/load/unload/license); acquisition worker steps control-plane only; COMPATIBILITY often NOT_CHECKED on cards.

---

### 3. FEAT-PLAYGROUND

| Field | Value |
|---|---|
| **featureId** | `FEAT-PLAYGROUND` |
| **Doc path** | `docs/70-features/local-playground.md` |
| **Module path** | `features/playground` → `:features:playground` |
| **FEATURE_ID** | `PlaygroundModule.FEATURE_ID = "FEAT-PLAYGROUND"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** TEXT_GENERATION, EMBEDDING, VISION_INPUT, AUDIO_INPUT, REQUEST_LIFECYCLE, SESSION_LIFECYCLE, STREAMING, CANCELLATION, DEADLINE, AIDL_INTERFACE, LOCAL_UI_INTERFACE, USABILITY_VALIDATION, ACCESSIBILITY_VALIDATION

| Capability | Covered? | Notes |
|---|---|---|
| TEXT_GENERATION | PARTIAL | `IOmniAdmin.executePlaygroundChat` → plane `playgroundApi.startChat` (exploratory CONDITIONAL; never invents SUPPORTED) |
| EMBEDDING | PARTIAL (plane/HTTP) / MISSING (Admin UI) | Admin `startEmbedding` returns CAPABILITY_UNKNOWN; `IOmniRuntime.embed` + HTTP `/v1/embeddings` exist |
| VISION_INPUT / AUDIO_INPUT | PARTIAL | UI tab `VISION_AUDIO`; capability negotiation fail-closes without evidence |
| STREAMING | PARTIAL | `IOmniRuntime.chat` + stream session; Admin chat is command/result strip (not full token stream UI) |
| STRUCTURED via tools tab | PARTIAL | Plane: `ToolsApiPlaygroundStructuredAdapter`; Admin: `FailClosedPlaygroundStructuredPort` |
| AIDL_INTERFACE | PASS (surface) | Chat/cancel/query on Admin; chat/embed/stream on Runtime |
| LOCAL_UI_INTERFACE | PASS (shell) | `PlaygroundScreen` tabs CHAT/EMBEDDINGS/VISION_AUDIO/STRUCTURED_TOOLS |

**UI:** Primary rail Playground.

**HTTP/AIDL:** `IOmniAdmin.executePlaygroundChat|queryPlaygroundRequest|cancelPlaygroundRequest`; `IOmniRuntime.chat|embed|cancelRequest`; HTTP chat/embeddings/async requests.

**Tests:** `PlaygroundCancelTest` (10), `PlaygroundCapabilityNegotiationTest` (14), `PlaygroundStreamEventsLocalUiGateTest` (2); runtime cancel ladder / SSE regression.

**Residuals:** Engine cells remain UNQUALIFIED; full multimodal + streaming UX incomplete on Admin path; structured tools fail-closed from UI binder projection.

---

### 4. FEAT-SERVER

| Field | Value |
|---|---|
| **featureId** | `FEAT-SERVER` |
| **Doc path** | `docs/70-features/developer-server.md` |
| **Module path** | `features/server` → `:features:server` |
| **FEATURE_ID** | `ServerFeatureModule.FEATURE_ID = "FEAT-SERVER"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** HTTP_INTERFACE, AIDL_INTERFACE, CAPABILITY_NEGOTIATION, TEXT_GENERATION, EMBEDDING, REQUEST_LIFECYCLE, STREAMING, CANCELLATION, DEADLINE, IDEMPOTENCY, RECONNECT, ASSET_LIFECYCLE

| Capability | Covered? | Notes |
|---|---|---|
| HTTP_INTERFACE | PASS (software) | `interfaces/http` OpenAPI paths + `LoopbackHttpGateway` + `ControlPlaneHttpHandler`; gateway lifecycle on plane |
| AIDL_INTERFACE | PASS (surface) | `IOmniRuntime` full surface; Admin `executeServerSmoke` |
| ASSET_LIFECYCLE | PASS (surface) | create/upload/commit/get/delete asset AIDL + HTTP `/omni/v1/assets/*` |
| IDEMPOTENCY / RECONNECT | PARTIAL | Claim/command ledger + tests; reconnect policy present in contracts |
| Token/client admin | PARTIAL | Plane Wave-A token service; Admin UI projection EmptyToken/EmptyClient |

**UI:** `ServerClientsScreen` (LOCAL tab) + server VM attached.

**HTTP:** `/v1/*`, `/omni/v1/*`, tokens, jobs, metrics, settings, assets — `OpenApiPaths` + OpenAPI smoke tests.

**Tests:** `DeveloperServerServiceTest` (13), `CapabilityNegotiationAndCancelTest` (10), `ServerStateProjectionTest` (7); HTTP gateway auth/route tests.

**Residuals:** Admin loopback status projects `enabled=false` + `loopback_status_not_projected_via_admin`; ensureStarted CAPABILITY_UNSUPPORTED on Admin path; smoke is exploratory honesty path.

---

### 5. FEAT-LAN

| Field | Value |
|---|---|
| **featureId** | `FEAT-LAN` |
| **Doc path** | `docs/70-features/lan-access.md` |
| **Module path** | `features/lan` → `:features:lan` |
| **FEATURE_ID** | `LanFeatureModule.FEATURE_ID = "FEAT-LAN"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** LAN_INTERFACE, HTTP_INTERFACE, CAPABILITY_NEGOTIATION, REQUEST_LIFECYCLE, IDEMPOTENCY, RECONNECT, ASSET_LIFECYCLE

| Capability | Covered? | Notes |
|---|---|---|
| LAN_INTERFACE | L2 strong | `ControlPlaneLanHost`, default-off check, TLS endpoint hooks, pairing policy constants |
| HTTP_INTERFACE | PASS (surface) | `enableLan`/`disableLan`/pairing challenges/exchanges in OpenAPI + handler |
| Pairing / clients from UI | MISSING→fail-closed | `FailClosedLanPairingPort`, `EmptyLanClientPort`, enable/disable CAPABILITY_UNSUPPORTED on Admin projection |

**UI:** `ServerClientsScreen` LAN tab + drawer `Lan` destination; VM attached but mutations fail-closed.

**HTTP/AIDL:** HTTP LAN routes; Admin snapshot `lanState` only (no enable AIDL method).

**Tests:** `LanServiceTest` (8), `LanAuthNegativeTest` (13), `LanPairingNegativeTest` (11), `LanPolicyTest` (15), `LanRoutingPolicyTest` (8); runtime `ControlPlaneLanHostTest`, `WireApi10HandlerTest`.

**Residuals:** Product default-off preserved; LOCAL_UI cannot enable/pair without additional Admin surface; device TLS bind not claimed PASS here.

---

### 6. FEAT-DASHBOARD

| Field | Value |
|---|---|
| **featureId** | `FEAT-DASHBOARD` |
| **Doc path** | `docs/70-features/dashboard-monitoring.md` |
| **Module path** | `features/dashboard` → `:features:dashboard` |
| **FEATURE_ID** | `DashboardFeatureModule.FEATURE_ID = "FEAT-DASHBOARD"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** SERVICE_HEALTH, ENGINE_HEALTH, MODEL_HEALTH, REQUEST_TRACE, JOB_PROGRESS, RESOURCE_ACCOUNTING, PERFORMANCE_MEASUREMENT, EVIDENCE_LABELING, LOCAL_UI_INTERFACE

| Capability | Covered? | Notes |
|---|---|---|
| SERVICE_HEALTH | PARTIAL | Runtime state from Admin snapshot; plane ObservabilityFacade |
| ENGINE/MODEL_HEALTH | PARTIAL | Plane subjects possible; Admin projection `subjects = emptyList()` |
| REQUEST_TRACE | MISSING on Admin | `getTrace` → CAPABILITY_UNSUPPORTED |
| JOB_PROGRESS | PARTIAL | Active jobs mapped as request rows |
| PERFORMANCE_MEASUREMENT | PARTIAL | Strip operational-only nulls on Admin; measurements late-bound from BenchmarkApi on plane |
| EVIDENCE_LABELING | L2 | Dashboard capability port `AllSupportedCapabilityPort` on plane (software observability — not engine QUALIFIED) |
| LOCAL_UI_INTERFACE | PASS (shell) | `DashboardScreen` primary rail |

**UI:** Primary Dashboard.

**HTTP:** `/omni/v1/metrics/summary`, `/omni/v1/metrics/detail`.

**Tests:** `DashboardServiceTest` (5), `DashboardProjectionTest` (9), `DashboardCapabilityCancelTest` (8).

**Residuals:** Admin projection is intentionally shallow; cancel/query request unsupported; negotiate always UNKNOWN cells on Admin path.

---

### 7. FEAT-BENCHMARK

| Field | Value |
|---|---|
| **featureId** | `FEAT-BENCHMARK` |
| **Doc path** | `docs/70-features/benchmark-research.md` |
| **Module path** | `features/benchmark` → `:features:benchmark` |
| **FEATURE_ID** | `BenchmarkFeatureModule.FEATURE_ID = "FEAT-BENCHMARK"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** PERFORMANCE_MEASUREMENT, EVIDENCE_LABELING, CAPABILITY_NEGOTIATION, JOB_LIFECYCLE, JOB_RECOVERY, RESOURCE_ACCOUNTING

| Capability | Covered? | Notes |
|---|---|---|
| PERFORMANCE_MEASUREMENT | PARTIAL | Plan + start BENCHMARK job; fixture metrics; export/completeRun plane-only |
| JOB_LIFECYCLE | PASS (software) | Admin `startJob(BENCHMARK)` / cancel / query |
| EVIDENCE_LABELING | L1/L2 | Policy + invalid force flags |
| CAPABILITY_NEGOTIATION | L1 tests | Negative policy tests |

**UI:** Drawer `BenchmarkScreen`; VM attached.

**HTTP/AIDL:** Job kind BENCHMARK via Admin; no dedicated OpenAPI benchmark resource beyond jobs.

**Tests:** `BenchmarkServiceTest` (9), `BenchmarkPolicyNegativeTest` (21), `ProfileComparisonAndSketchTest` (6), `FixtureMetricsAndProfileFactoryTest` (2).

**Residuals:** `listRuns`/`currentRun` empty on Admin; `exportReport` CAPABILITY_UNSUPPORTED on Admin; device measurement validity not claimed.

---

### 8. FEAT-DIAGNOSTICS

| Field | Value |
|---|---|
| **featureId** | `FEAT-DIAGNOSTICS` |
| **Doc path** | `docs/70-features/diagnostics-export.md` |
| **Module path** | `features/diagnostics` → `:features:diagnostics` |
| **FEATURE_ID** | `DiagnosticsModule.FEATURE_ID = "FEAT-DIAGNOSTICS"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** DIAGNOSTIC_REASONING, REQUEST_TRACE, SERVICE_HEALTH, EVIDENCE_LABELING, JOB_LIFECYCLE, JOB_RECOVERY, LOCAL_UI_INTERFACE

| Capability | Covered? | Notes |
|---|---|---|
| DIAGNOSTIC_REASONING / export | L2 | `DiagnosticsService`, bundle builder, DIAGNOSTIC_EXPORT job kind |
| LOCAL_UI_INTERFACE | PARTIAL | `DiagnosticsScreen` exists; **`diagnosticsVm` never attached** in `UiSession.adminListener` |
| JOB_LIFECYCLE | L2 | JobManager + HTTP `createDiagnosticExport` |

**UI:** Drawer Diagnostics — permanently EMPTY without external inject.

**HTTP/AIDL:** HTTP `/omni/v1/diagnostics/exports`; job kind DIAGNOSTIC_EXPORT in Admin mapper; no dedicated diagnostics AIDL methods.

**Tests:** `DiagnosticsServiceTest` (7), `DiagnosticBundleBuilderTest` (5), `CapabilityNegotiationAndCancelTest` (6), `DiagnosticStateProjectionTest` (7).

**Residuals:** Wire UI factory for DiagnosticsViewModel from plane/Admin; default source port is `EmptyDiagnosticSourcePort` unless filled.

---

### 9. FEAT-ROUTING

| Field | Value |
|---|---|
| **featureId** | `FEAT-ROUTING` |
| **Doc path** | `docs/70-features/multi-model-routing.md` |
| **Module path** | `features/routing` → `:features:routing` |
| **FEATURE_ID** | `RoutingFeatureModule.FEATURE_ID = "FEAT-ROUTING"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** MULTI_MODEL_ROUTING, FALLBACK_POLICY, CAPABILITY_NEGOTIATION, REQUEST_LIFECYCLE, RESOURCE_ACCOUNTING

| Capability | Covered? | Notes |
|---|---|---|
| MULTI_MODEL_ROUTING | L2 | `RoutingService` + orchestrator planner when Wave-A present |
| FALLBACK_POLICY | L1/L2 | `FallbackPolicyRules` NONE / SAME_REVISION_ONLY / ALLOW_LIST; no silent cross-revision |
| LOCAL_UI | PARTIAL | `RoutingScreen` expert drawer; **`routingVm` never attached** |

**UI:** Drawer Routing — EMPTY without inject.

**HTTP/AIDL:** No dedicated routing HTTP resource; routing used inside inference plan path on plane.

**Tests:** `RoutingServiceTest` (11), `FallbackPolicyRulesTest` (12), `RoutingNegativeCasesTest` (10), `AliasResolutionTest` (4), `SessionContinuityPolicyTest` (6), `RoutingProjectionTest` (3); runtime `featurePackHost_withWaveA_bindsRoutingToOrchestrator`.

**Residuals:** Attach RoutingViewModel; preference persistence surface for LOCAL_UI.

---

### 10. FEAT-TOOLS

| Field | Value |
|---|---|
| **featureId** | `FEAT-TOOLS` |
| **Doc path** | `docs/70-features/structured-tools.md` |
| **Module path** | `features/tools` → `:features:tools` |
| **FEATURE_ID** | `ToolsFeatureModule.FEATURE_ID = "FEAT-TOOLS"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** STRUCTURED_OUTPUT, TOOL_CALLING, TEXT_GENERATION, REQUEST_LIFECYCLE, STREAMING, CANCELLATION, DEADLINE

| Capability | Covered? | Notes |
|---|---|---|
| STRUCTURED_OUTPUT / TOOL_CALLING | L2 | `ToolsService`, schema admission, non-execution of host tools (proposal ledger only) |
| Durable ledger | L2 | `createDurableApi` + SQLite tool proposal ledger when plane provides ports |
| LOCAL_UI structured | PARTIAL | Playground STRUCTURED_TOOLS tab; Admin uses `FailClosedPlaygroundStructuredPort`; plane late-binds ToolsApi into playground adapter |
| Dedicated tools UI | MISSING | No Tools destination; only playground tab |

**UI:** Nested under Playground tab only.

**HTTP/AIDL:** Tools reachable via control-plane playground structured adapter; HTTP markers include tools; no dedicated `/tools` OpenAPI resource beyond structured chat.

**Tests:** `ToolNonExecutionAndIdempotencyTest` (7), `SchemaBombTest` (7), `StructuredModePolicyTest` (9), `StructuredOutputValidatorTest` (5), `RoutingAndAuthNegativeTest` (9), `SessionUncertaintyAndPrivacyTest` (7).

**Residuals:** Expose structured path on Admin AIDL or drop fail-closed projection; engine structured mode remains UNKNOWN without qualification.

---

### 11. FEAT-ADMIN

| Field | Value |
|---|---|
| **featureId** | `FEAT-ADMIN` |
| **Doc path** | `docs/70-features/administration-jobs.md` |
| **Module path** | `features/admin` → `:features:admin` |
| **FEATURE_ID** | `AdminFeatureModule.FEATURE_ID = "FEAT-ADMIN"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities:** ADMIN_INTERFACE, AIDL_INTERFACE, JOB_LIFECYCLE, JOB_RECOVERY, MODEL_LIFECYCLE, ENGINE_LIFECYCLE, RESOURCE_ACCOUNTING, LOCAL_UI_INTERFACE

| Capability | Covered? | Notes |
|---|---|---|
| ADMIN_INTERFACE | PASS (software) | Snapshot, settings, jobs, command query via ports |
| AIDL_INTERFACE | PASS | `IOmniAdmin` non-exported; `BinderAdminFeatureFactory` |
| JOB_LIFECYCLE | PASS (software) | start/get/cancel/observe jobs |
| MODEL_LIFECYCLE | PARTIAL | Plane AdminModelPort lists installations; binder path defaults `EmptyAdminModelPort` |
| ENGINE_LIFECYCLE | PARTIAL | No direct engine lifecycle UI; engine attach is control-plane |
| LOCAL_UI_INTERFACE | PASS | Home / Settings / job chrome |

**UI:** Home primary; Settings drawer; jobs via Home.

**HTTP/AIDL:** Core Admin AIDL; HTTP settings/jobs/clients partial overlap.

**Tests:** `AdminFeatureHappyPathTest` (6), `AdminFeatureCancelRecoverTest` (8), `JobUiProjectionTest` (6).

**Residuals:** Populate AdminModelPort on binder path; resource pressure label null in Wave-A wiring.

---

### 12. FEAT-AI-CONTENT-REPORT (alias FEAT-AI-REPORTING)

| Field | Value |
|---|---|
| **featureId** | `FEAT-AI-CONTENT-REPORT` (code/prose) / `FEAT-AI-REPORTING` (docs package YAML) |
| **Doc path** | `docs/70-features/ai-content-reporting.md` |
| **Module path** | `features/ai-content-report` → `:features:ai-content-report` |
| **FEATURE_ID** | `ContentReportModule.FEATURE_ID = "FEAT-AI-CONTENT-REPORT"` |
| **L1/L2/L3** | PASS / PASS / **PARTIAL** |

**Capabilities (YAML under FEAT-AI-REPORTING):** CONTENT_REPORTING, REQUEST_LIFECYCLE, JOB_LIFECYCLE, JOB_RECOVERY, LOCAL_UI_INTERFACE, USABILITY_VALIDATION, ACCESSIBILITY_VALIDATION

| Capability | Covered? | Notes |
|---|---|---|
| CONTENT_REPORTING | L2 | Full FSM service, durable store + secret broker, not-telemetry policy checks at bootstrap |
| JOB_LIFECYCLE | L2 | CONTENT_REPORT job kind |
| LOCAL_UI_INTERFACE | PARTIAL | `ContentReportScreen` + review/submit on `IOmniAdmin`; **`contentReportVm` never attached** in UiSession listener |
| External proposal | L2 | `IOmniRuntime.createContentReportProposal` + HTTP content-reports |

**UI:** Drawer Content report — EMPTY without inject (review actions exist on binder but no live VM).

**HTTP/AIDL:** Full content-report AIDL on Runtime + Admin review/submit/receipt; HTTP `/omni/v1/content-reports*`.

**Tests:** FullFlow (13), Policy (13), RaceAndService (12), NegativeConsent (6), DurableStore (5), AtomicProposal (2) — all 0 failures.

**Residuals:** ID alias governance; attach ContentReportViewModel; NoOp external endpoint until configured.

---

### 13. FEATURE-SYSTEM (non-pack)

| Field | Value |
|---|---|
| **id** | `FEATURE-SYSTEM` |
| **Doc** | `docs/70-features/feature-design-system.md` |
| **Module** | N/A |
| **L1/L2/L3** | **N_A** / **N_A** / **N_A** |
| **status** | **N_A** |

Design system for how packs compose platform capabilities — not a product journey. Referenced by every feature module KDoc (“does not redefine Request/Session/Trust”).

---

## Cross-cutting residuals

1. **UI attach gap:** `diagnosticsVm`, `contentReportVm`, `routingVm` never set in `UiSession.adminListener` → three drawer journeys permanently EMPTY.
2. **Admin projection honesty vs completeness:** Many mutations fail-closed with CAPABILITY_UNSUPPORTED (LAN enable, load/pin, token issue, structured tools, dashboard traces) while control-plane APIs implement them — L2 > L3.
3. **Engine qualification:** All exploratory execute paths document CONDITIONAL/UNQUALIFIED; **no engine marked QUALIFIED/SUPPORTED** in this audit.
4. **ID drift:** `FEAT-AI-REPORTING` (docs YAML) vs `FEAT-AI-CONTENT-REPORT` (prose + code).
5. **No new FEAT-* beyond the 12** found in 新版本 docs package.
6. **Tests:** Feature-module unit suites present with 0 failures in last `build/test-results` XML; does **not** prove on-device E2E (Appium artifacts exist elsewhere; not promoted to FEAT L3 PASS).

---

## Search / evidence log (empty-finding discipline)

Grep/read performed for this matrix (non-exhaustive of every file, sufficient for status):

- Docs: `docs/70-features/*`, `specs/feature-capability-map.yaml`
- Modules: `features/*/src/main/kotlin/**/*Module.kt`, services, viewmodels
- Host: `FeaturePackHost.kt`, `WaveAWiring.kt`, `RuntimeControlPlane.kt`, `ControlPlaneHttpHandler.kt`, facades
- UI: `OmniDestinations.kt`, `OmniNavHost.kt`, `UiSession.kt`, `AdminLiveFeatureFactory.kt`, `AdminFeatureProjections.kt`, all `screens/*`
- AIDL: `IOmniAdmin.aidl`, `IOmniRuntime.aidl`
- HTTP: `OpenApiPaths.kt`, gateway/tests
- Tests: all `features/*/build/test-results/test/TEST-*.xml` (summarized above)

No FEAT pack directory missing under monorepo `features/`.

---

## Verdict

| Metric | Result |
|---|---|
| L1 PASS count | **12 / 12** product FEATs |
| L2 PASS count | **12 / 12** product FEATs |
| L3 PASS count | **0 / 12** |
| Overall PASS | **0** |
| Overall PARTIAL | **12** |
| N_A | FEATURE-SYSTEM |

**Software architecture readiness for feature packs is high (L1+L2).** Product journeys are **not** software-complete for L3 due to UI binding gaps and Admin projection fail-closed surfaces — not due to missing modules.
