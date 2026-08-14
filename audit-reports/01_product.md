# 01_product — PRODUCT / QUALITY / MODES Audit

| Field | Value |
|-------|--------|
| **Artifact** | `01_product.md` |
| **Audit date (UTC host)** | 2026-08-12 |
| **Docs authority** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo under audit** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Primary docs** | `docs/00-product/*` (+ specs when conflicting) |
| **Method** | `list_dir` / `read_file` / `grep` on real paths; fail-closed |
| **Status labels** | `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN` only |
| **L1 / L2 / L3** | L1 = module/tree exists · L2 = wired to control plane · L3 = product journey software-complete |

> Specs under docs package `specs/` take precedence over prose when IDs conflict.  
> Engines are **not** marked QUALIFIED/SUPPORTED: monorepo + docs `engine-qualification-status.yaml` keep all cells `UNQUALIFIED` / `UNKNOWN`.  
> No device PASS, Play upload, or OEM matrix results are invented here.

---

## Executive summary

| Major section | Status | One-line verdict |
|---|---|---|
| **PROD-CHARTER — core values → modules** | **PARTIAL** | Three value packs exist as L1 features + shared Orchestrator L2; no engine-qualified first-success L3. |
| **PROD-CAPABILITY-MODEL — states / negotiation** | **PARTIAL** | Five-state `CapabilityState` + negotiation ports exist; dashboard default invents all-`SUPPORTED`; full evidence-envelope binding incomplete on wire. |
| **PROD-CAPABILITY-ELIGIBILITY** | **PARTIAL** | Design/build/runtime separation present in specs + `ProductBuildMode` / exploratory flags; runtime default remains UNKNOWN/UNQUALIFIED. |
| **PROD-MODES — not forked products** | **PARTIAL** | Single platform + Orchestrator (not forked apps); Research/Risky gates are L1 policy only (not L2-consumed); Local/Server/LAN L1–L2 present. |
| **PROD-QUALITY — evidence labels & quality attributes** | **PARTIAL** | `EvidenceLabel` + `EvidenceSemantics` + UI chips are real; quality scenarios almost all `NOT_EXECUTED`. |
| **PROD-BOUNDARIES** | **PARTIAL** | Local-first / no cloud-account core / companion ≠ same-UID sandbox encoded; product-boundary claims not all journey-proven. |
| **PROD-GLOSSARY** | **PARTIAL** | Core identity/resource terms exist as types; no automated glossary completeness gate. |
| **PROD-PERSONAS / JTBD** | **PARTIAL** | Screens + feature packs map to personas; researcher/risky UX and full first-success JTBD incomplete. |

**Overall product layer (this audit scope):** **PARTIAL**

---

## Search / evidence footprint

| Target | Patterns / files read |
|---|---|
| Docs `docs/00-product/` | charter, capability model, eligibility, modes, quality, boundaries, glossary, personas, README |
| Docs specs | `capability-catalog.yaml`, `capability-availability-matrix.yaml`, `quality-scenarios.yaml`, `feature-capability-map.yaml`, `canonical-types.yaml` |
| Monorepo specs | same basenames under `omnillm-android/specs/` |
| Canonical codegen | `core/canonical/.../CanonicalEnums.kt`, `CapabilityCatalog.kt` |
| Modes | `ProductModePolicy.kt`, `ConfigurationCatalog.kt`, `configuration-catalog.yaml`, Settings UI, grep `researchMode` / `riskyPerformance` under `android/` |
| Capability runtime | `EngineExecuteBinding.kt`, `EngineRegistry.kt`, `ControlPlaneFeaturePorts.kt`, `WireDtos.kt`, server negotiation |
| Evidence | `EvidenceSemantics.kt`, `CommonUi.kt` `EvidenceChip`, dashboard/benchmark metrics, observability tests |
| Core-value wiring | `WaveAWiring.kt`, `FeaturePackHost.kt`, `OmniDestinations.kt`, feature modules under `features/*` |
| Risky / sandbox | `CompanionPlacementGate.kt`, `android/companion-sandbox/**` |
| Quality scenarios | monorepo `specs/quality-scenarios.yaml` (`evidenceStatus`) |
| Qualification | `specs/engine-qualification-status.yaml` (all UNQUALIFIED) |

Empty-finding notes are called out per section where greps returned no L2 consumers.

---

## 1. PROD-CHARTER — mission, core values, commitments

**Docs:** `docs/00-product/product-charter.md` (`PROD-CHARTER`)

### 1.1 Core value → module trace

| Core value (charter §2) | Expected capabilities / features | Code evidence | L1 | L2 | L3 | Status |
|---|---|---|---|---|---|---|
| **2.1 低技術門檻自動架設** | DEVICE_DISCOVERY, RECOMMENDATION, MODEL_ACQUISITION, AUTOMATED_CONFIGURATION, SAFE_INSTALLATION; FEAT-AUTOSETUP / MODELHUB | `features/auto-setup/` (`AutoSetupService`, `RecommendationRanker`, `AutomatedConfigurationBuilder`); `features/modelhub/`; Wave-A host wires both: `WaveAWiring.kt` creates `AutoSetupModule.createApi` + `ModelhubModule.createApi`; UI `OnboardingScreen.kt`, `ModelHubScreen.kt`, nav `OmniDestinations.Onboarding` / `ModelHub` | Yes | Yes (control plane packs) | No — engines UNQUALIFIED; exploratory CONDITIONAL only; no device-qualified first success | **PARTIAL** |
| **2.2 統一調用** | CAPABILITY_NEGOTIATION, REQUEST/SESSION lifecycle, HTTP/AIDL, shared Orchestrator | Single `Orchestrator` in `WaveAWiring` shared by playground/server/routing ports (`ControlPlaneFeaturePorts` `Orchestrator*InferencePort`); `interfaces/http`, `interfaces/aidl`, `features/server`, `features/playground`, `features/routing`, `features/tools` | Yes | Yes (one orchestrator + capability lookup) | No — multi-engine execute incomplete; TEXT_GENERATION exploratory; EMBEDDING/STRUCTURED_OUTPUT/TOOL_CALLING marked not-yet-implemented in `EngineExecuteBinding` | **PARTIAL** |
| **2.3 可視化監控** | SERVICE_HEALTH, RESOURCE_ACCOUNTING, PERFORMANCE_MEASUREMENT, DIAGNOSTIC_REASONING, EVIDENCE_LABELING; FEAT-DASHBOARD / DIAGNOSTICS | `features/dashboard`, `features/diagnostics`, `runtime/observability` (`EvidenceSemantics`, metric registry); UI `DashboardScreen.kt`, `DiagnosticsScreen.kt`; Wave-A attaches dashboard to `ObservabilityFacade` + `GovernorResourceAdapter` | Yes | Yes | Partial software journey only; no MEASURED device SLO campaign | **PARTIAL** |

**Charter design-acceptance hooks (charter §7):**

| Acceptance idea | Evidence | Status |
|---|---|---|
| Three core values map to journeys + capabilities + observability | Feature-capability map exists (`specs/feature-capability-map.yaml`); modules map 1:1 to FEAT packs (see `00_MAP.md`) | **PARTIAL** (map + L1/L2; L3 incomplete) |
| Safety copy → isolation mechanism | Companion package + placement gate (see §6); crash vs permission isolation types in engines API | **PARTIAL** |
| Performance claims → measurement profile or ESTIMATED | `features/benchmark` has `MeasurementProfile` / `MeasurementRun*`; `EvidenceLabel` enum; Q-scenarios NOT_EXECUTED | **PARTIAL** |
| Sixth engine / new transport without rewriting core semantics | Five engine packs + `engines/api` registry; HTTP + AIDL + admin; capability catalog generated | **PARTIAL** (architecture present; no claim of production multi-engine parity) |

**Section 1 status: PARTIAL**

---

## 2. PROD-CAPABILITY-MODEL — catalog, states, non-boolean

**Docs:** `docs/00-product/core-value-capability-model.md`  
**Specs (prefer):** `specs/capability-catalog.yaml` (docs package & monorepo)

### 2.1 Capability catalog presence

| Check | Evidence | Status |
|---|---|---|
| Catalog IDs generated into code | `core/canonical/.../CapabilityCatalog.kt` — `CapabilityId` enum includes DEVICE_DISCOVERY … ACCESSIBILITY_VALIDATION; `REQUIRED_EVIDENCE_BINDING` lists `engineBuildId`, `backend`, `deviceFingerprint`, `modelEnvelope`, `operationProfile`, `evidenceId`, `expiresAt` | **PASS** (L1 contract) |
| Spec states match prose | `CapabilityState` / `CapabilityAvailability`: SUPPORTED, UNSUPPORTED, CONDITIONAL, UNKNOWN, TEMPORARILY_UNAVAILABLE — `CanonicalEnums.kt` + `capability-catalog.yaml` | **PASS** |
| Feature only composes capabilities | Dashboard module comment: does not redefine Request/Session/Trust (`DashboardFeatureModule.kt`); Server negotiates via projection | **PARTIAL** (pattern present; not proven for every pack) |

### 2.2 Capability states not boolean-only

| Check | Evidence | Status |
|---|---|---|
| Negotiation returns multi-state | `EngineExecuteBinding.resolveCapability` → UNKNOWN / CONDITIONAL (never plain SUPPORTED without PASS); conditions list `exploratory_execute_enabled`, `engine_unqualified`, `development_ship_mode`, … | **PASS** (inference path) |
| Engine registry projection | `engines/api/EngineRegistry.kt`: `resolveCapability` / `projectRuntimeCapability` — only `QUALIFIED_WITH_ENVELOPE` + evidence `PASS` → SUPPORTED; missing cell → UNKNOWN; helper `isSupported` is derived, not the sole model | **PASS** |
| Wire CapabilityEntry carries state + evidence_label | `CapabilityEntryDto` (`WireDtos.kt`): `capabilityId`, `state: CapabilityState`, `evidenceLabel: EvidenceLabel`, optional `reasonCode` | **PARTIAL** — required full binding (`evidenceId`, envelope, `expiresAt`) **not** on this DTO |
| Server negotiation multi-kind blockers | `CapabilityNegotiationResult` + `CapabilityBlockerKind` (UNSUPPORTED / UNKNOWN / MISSING / TEMPORARILY_UNAVAILABLE) in `features/server` | **PASS** (feature logic) |
| **Anti-pattern: invent all SUPPORTED** | `AllSupportedCapabilityPort` in `DashboardPorts.kt`: `state(...) = CapabilityState.SUPPORTED` for every catalog id; production Wave-A wires it: `WaveAWiring.kt` lines ~428–433 with comment “software-side SUPPORTED” | **PARTIAL / gap** — violates “global boolean / fake SUPPORTED” spirit for **engine** cells when used as a blanket for all `CapabilityId`s on dashboard |

### 2.3 Capability group coverage (L1 existence only)

| Group (model §3) | Representative IDs | Module / port evidence | Status |
|---|---|---|---|
| 自動架設 | DEVICE_DISCOVERY … SAFE_INSTALLATION | `features/auto-setup`, `features/modelhub`, `data/model-store` | **PARTIAL** |
| 統一調用 | TEXT_GENERATION, STREAMING, HTTP/AIDL, MULTI_MODEL_ROUTING | `runtime/orchestrator`, `interfaces/*`, `features/{playground,server,routing,tools,lan}` | **PARTIAL** |
| 可視化 | SERVICE_HEALTH … EVIDENCE_LABELING | `runtime/observability`, `features/{dashboard,diagnostics,benchmark}` | **PARTIAL** |

**Section 2 status: PARTIAL**

---

## 3. PROD-CAPABILITY-ELIGIBILITY — design / build / runtime split

**Docs:** `docs/00-product/capability-availability-and-build-eligibility.md`  
**Specs:** `specs/capability-availability-matrix.yaml`

| Rule | Evidence | Status |
|---|---|---|
| Design status ≠ runtime availability | Matrix: each capability `designStatus: DESIGN_BASELINE`, `buildEligibility: BUILD_ELIGIBLE`, `runtimeDefault: UNKNOWN`, `qualification: QUALIFICATION_REQUIRED`, fail-closed text present (monorepo + docs package) | **PASS** (spec L1) |
| UNKNOWN must not be pretended SUPPORTED | `EngineRegistry.projectRuntimeCapability`; `EngineExecuteBinding` COR-10 comments; `ProductBuildMode` forbids plain SUPPORTED without PASS even in dev (uses CONDITIONAL + `development_ship_mode`) | **PASS** (engine path) |
| Unqualified engines do not block other cells | Multiple engine modules registered independently; qualification YAML all UNQUALIFIED | **PASS** (structure) |
| UI/HTTP may show designed capabilities but execute only SUPPORTED/CONDITIONAL | Exploratory execute gated by `runtime.exploratoryExecuteEnabled` (catalog + Settings switch); AIDL notes in `specs/aidl/omnillm-aidl.yaml` | **PARTIAL** (software gates exist; device execute not qualified) |
| Build posture separate from qualification | `core/contracts/ProductBuildMode.kt` (dev vs fail-closed); does **not** flip YAML to SUPPORTED | **PASS** |

**Section 3 status: PARTIAL** (specs + honest defaults **PASS**; end-to-end availability matrix enforcement per cell incomplete → overall PARTIAL)

---

## 4. PROD-MODES — modes are not forked products

**Docs:** `docs/00-product/product-modes.md` (`PROD-MODES`)

### 4.1 Architectural invariant: one platform

| Check | Evidence | Status |
|---|---|---|
| Not separate product forks | Single Gradle app `:android:app-ui` + `:android:runtime-service`; feature packs attached via `FeaturePackHost` + `WaveAWiring`; shared `Orchestrator` instance for playground/server/tools/routing | **PASS** (L2 architecture) |
| Modes change entry / density / auth, not canonical semantics | Density is UI (`DensityMode` STANDARD/EXPERT) separate from product-mode flags; Server/LAN use same inference ports through Orchestrator | **PASS** (pattern) |

### 4.2 Mode-by-mode

| Mode (docs) | Expected behavior | Code evidence | L1 | L2 | L3 | Status |
|---|---|---|---|---|---|---|
| **Local User Mode** (default) | App: device detect, model acquire, chat, dashboard, diagnostics; advanced hidden | Primary rail: Home, ModelHub, Playground, ServerClients, Dashboard (`OmniDestinations.kt`); Settings/Diagnostics/Onboarding under “more” | Yes | Yes | Partial UI journeys only | **PARTIAL** |
| **Developer Server Mode** | Loopback HTTP / AIDL; named principal + scoped token; same Orchestrator/Governor | `features/server` (`DeveloperServerService`, capability negotiate, tokens); `server.loopbackEnabled` catalog default **false**; `GatewayLifecycle` / `LoopbackTokenService`; AIDL facades under `runtime-service/binder` | Yes | Yes | No device multi-client proof | **PARTIAL** |
| **LAN Mode** | Default off; TLS; short pairing; connection epoch; scoped token; no Host/CORS auth; no long-lived secret in QR | `features/lan`: `LanServiceLifecyclePolicy.DEFAULT_ENABLED = false`, `SETTING_KEY` ↔ `server.lanEnabled`; `LanAuthPolicy` (loopback tokens rejected on LAN); `QrPayloadPolicy` forbids long-lived bearer/private keys; `ControlPlaneLanHost` + `LanTlsEndpoint` | Yes | Yes (host bootstrap) | No real-device LAN pairing audit | **PARTIAL** |
| **Research Mode** | Full measurement profile, backend detail, raw diagnostics; same trust/admission | Catalog keys `product.researchModeEnabled` (default false) in `ConfigurationCatalog` + `specs/configuration-catalog.yaml`; `ProductModePolicy.projectedModes` maps to `rawDiagnosticsAllowed` / `backendSelectionOptionsAllowed` | Yes (policy) | **No** — **grep under `android/` for `researchMode` / `ProductModePolicy` / `rawDiagnosticsAllowed` = no consumers**; not referenced from diagnostics/benchmark/UI beyond generic settings field dump | No | **PARTIAL** (L1 only; L2 MISSING) |
| **Risky Performance Mode** | Explicit user risk; untrusted accel → different package/UID companion; else signed-only | Catalog `product.riskyPerformanceModeEnabled` + `ProductModePolicy.requireRiskyPerformanceAccess` (RiskAck one-shot) + unit tests `ProductModePolicyTest`; companion: `android/companion-sandbox`, `CompanionPlacementGate` (never same-UID security sandbox for EXTERNAL_UID_ACCELERATED) | Yes (policy + companion module) | **RiskAck gate not wired into placement/execute path** (only policy module + tests); companion gate is separate trust path | No | **PARTIAL** |

### 4.3 Mode-switch invariants (docs §7)

| Invariant | Evidence | Status |
|---|---|---|
| Switch does not change model identity / request semantics | Modes are settings flags, not alternate orchestrators | **PASS** (by structure) |
| Closing risky mode → future placement only; rotate tokens / kill workers | Revocation epoch on tokens/sessions/persistence (`SqlDelightSecretLedgerStore`, session pool keys); **not** explicitly tied to product-mode disable handlers | **PARTIAL** |
| Revocation bumps epoch, cancels streams | Token revoke bumps epoch in WaveA server token port; LAN epoch bump policy; session poison tests | **PARTIAL** |

### 4.4 Settings UX for modes

| Check | Evidence | Status |
|---|---|---|
| Exploratory execute toggle | `SettingsScreen.kt` special-cases `runtime.exploratoryExecuteEnabled` with honest copy “never invents SUPPORTED” | **PASS** (L3 software for this flag) |
| Research / Risky dedicated UX | No dedicated mode section; product keys may appear only as generic admin field list if exported by Admin API | **MISSING** (dedicated UX) |

**Section 4 status: PARTIAL**

---

## 5. PROD-QUALITY — quality attributes & evidence labels

**Docs:** `docs/00-product/quality-and-success-model.md`  
**Specs:** `specs/quality-scenarios.yaml`, `specs/observability-catalog.yaml`, `specs/canonical-types.yaml`

### 5.1 Evidence labels (MEASURED / ESTIMATED / REPORTED / LAST_SAMPLED / UNKNOWN)

| Check | Evidence | Status |
|---|---|---|
| Enum complete | `EvidenceLabel` in `CanonicalEnums.kt` + OpenAPI + `observability-catalog.yaml` | **PASS** |
| Semantic rules encoded | `runtime/observability/EvidenceSemantics.kt` — UNKNOWN never numeric zero; REPORTED requires `source`; ESTIMATED confidence/error fields; only MEASURED may drive hard admission | **PASS** |
| UI projection | `EvidenceChip` / `EvidencedMetricRow` in `CommonUi.kt`; used by `DashboardScreen`, `DiagnosticsScreen`, `PlaygroundScreen` | **PASS** (L2/L3 software) |
| Wire metrics require label | `WireMetricsContractTest`, `MetricSample` DTOs with `evidence_label` | **PASS** (contract tests) |
| Capability cells carry label | Control-plane server capabilities set REPORTED vs UNKNOWN (`ControlPlaneFeaturePorts`); HTTP `CapabilityEntryDto` | **PARTIAL** |
| Real MEASURED device profiles | Benchmark domain models exist; quality scenarios evidenceStatus almost all `NOT_EXECUTED` (only Q-017 docs lint PASS in package) | **PARTIAL** / fail-closed: **no device MEASURED campaign claimed** |

### 5.2 Quality attributes (docs §2) → software evidence

| Attribute | Observable design intent | Monorepo evidence | Status |
|---|---|---|---|
| 可完成性 | Journey completion | Auto-setup journey phases; Appium e2e report exists as **non-authority** smoke (not re-certified here) | **PARTIAL** |
| 語義正確性 | Contract conformance | Golden identity tests; OpenAPI wire tests; AIDL authority list | **PARTIAL** |
| 安全 | Placement / revocation / negative tests | Companion gate, LAN auth policy, token epoch, policy tests | **PARTIAL** |
| 資源可預期 | Reservation before side effects | `runtime/governor/ResourceGovernor`, ledger charge/reserve APIs | **PARTIAL** |
| 可恢復性 | Query/reconcile / poison | Commit ledger, poisoned session tests, recovery fixtures YAML | **PARTIAL** |
| 性能 | Profile-bound metrics | Benchmark `MeasurementProfile` / runs; no executed Q-001 envelope campaign | **PARTIAL** |
| 可理解性 | State catalog / diagnostics | UX projection specs; diagnostics feature; evidence chips | **PARTIAL** |
| 可擴充性 | Adapter isolation | Engine packs + `engines/api` | **PARTIAL** |

### 5.3 Quality scenarios execution

Monorepo `specs/quality-scenarios.yaml` (aligned with docs package): Q-001…Q-016, Q-018…Q-020 marked `evidenceStatus: NOT_EXECUTED`; Q-017 repository lint `PASS` (docs package validator).

| Check | Status |
|---|---|
| Scenario catalog present in monorepo | **PASS** (file present) |
| Scenarios executed as product evidence | **MISSING** (except Q-017 docs lint — documentation tooling, not product runtime) |
| SLO env/workload/percentile discipline in running product | **MISSING** as operational evidence (design-only) |

**Section 5 status: PARTIAL**

---

## 6. PROD-BOUNDARIES — coverage & non-goals

**Docs:** `docs/00-product/coverage-and-boundaries.md`

| Boundary claim | Evidence | Status |
|---|---|---|
| No cloud account / cloud inference billing as core | No monorepo identity provider for cloud accounts found; local-first settings (`privacy.telemetryMode` LOCAL_ONLY default); Play Data Safety inventory warns against “AI training with user content” without consent | **PASS** (absence + local defaults; not a formal threat proof) |
| Not a training / fine-tune platform | No FEAT for training; model acquisition/install only | **PASS** (scope) |
| Same-UID worker ≠ security sandbox | `CompanionPlacementGate` comments + refuse EXTERNAL_UID_ACCELERATED without companion; companion separate package module | **PASS** (L1/L2 policy code) |
| Dry-load ≠ source authenticity | Engine qualification vs supply-chain lock comments in `engine-qualification-status.yaml` (“complete supply-chain lock is NOT a SUPPORTED claim”) | **PASS** (honest docs/specs in repo) |
| OpenAI-compatible only as listed HTTP profile | HTTP gateway + mllm OpenAI-compatible protocol tests; not full third-party API parity claim in product code | **PARTIAL** |
| Faster ≠ safer in recommendations | Auto-setup ranker / preferences present; no audited proof that UI never equates speed with safety | **PARTIAL** / fail-closed without ranking-policy deep audit |

**Section 6 status: PARTIAL**

---

## 7. PROD-GLOSSARY — term materialization

**Docs:** `docs/00-product/glossary.md`

| Term sample | Code symbol / path | Status |
|---|---|---|
| BlobId / ModelRevisionId | `core/canonical` generated digests; `core/identity/ModelRevision.kt` | **PASS** |
| InstallationId | identity + model-manager domain | **PASS** |
| EngineBuildId / LoadKey concepts | `core/contracts`, engine modules | **PASS** |
| Capability / Compatibility evidence | `CapabilityState`, `EngineRegistry` cells | **PASS** |
| Trust Placement / Crash containment / Permission isolation | `PlacementClassLabels`, `TrustPlacementPolicy`, companion package | **PASS** |
| Reservation / AllocationHandle | `core/resource`, `ResourceGovernor` | **PASS** |
| Session / Poisoned Session / Delivered checkpoint | `runtime/session`, transport delivery guarantees tests | **PASS** |
| MeasurementProfileId / MeasurementRunId | `features/benchmark` domain types | **PASS** |
| Principal / Revocation epoch | policy security, persistence secret ledger, token views | **PASS** |
| Full glossary automated completeness | No glossary→code CI matrix found | **PARTIAL** |

**Section 7 status: PARTIAL** (major terms **PASS** individually; whole-table gate not present)

---

## 8. PROD-PERSONAS / JTBD

**Docs:** `docs/00-product/personas-and-jtbd.md`

| Persona | Success condition (summary) | Code / UI evidence | Status |
|---|---|---|---|
| 本機使用者 | Preferences-led first run; explain recommendations; risk language; recovery next steps | Onboarding + AutoSetup + ModelHub + Playground screens; exploratory risk copy in Settings | **PARTIAL** |
| 應用開發者 | Capability query before send; idempotency; consistent unsupported errors; token revoke | Server negotiate API; HTTP OpenAPI; token revoke epoch; request registry | **PARTIAL** |
| 研究者 | Measurement profile completeness; run history; comparability UI | Benchmark feature + MeasurementProfile factory; Research mode **not** L2-gated | **PARTIAL** |
| 平台維護者 | Engine integration packs independent | `engines/*` packs + UPSTREAM.lock + qualification YAML | **PARTIAL** |
| 安全／合規 | Threat→control trace; untrusted native not co-UID with secrets | Companion sandbox + security catalogs; not full threat matrix executed | **PARTIAL** |

**Section 8 status: PARTIAL**

---

## 9. Cross-cutting integrity checks (this audit’s hard rules)

| Hard rule | Result | Evidence |
|---|---|---|
| No QUALIFIED/SUPPORTED engine claims without evidence | **Held** | `specs/engine-qualification-status.yaml` — all `qualificationStatus: UNQUALIFIED`, `runtimeCapabilityDefault: UNKNOWN`; registry projection never invents SUPPORTED without PASS |
| No invented device / Play / OEM PASS | **Held** | This report claims none |
| Fail closed without path evidence | **Held** | Research mode L2 marked missing after empty `android/` grep |
| L1 vs L2 vs L3 distinguished | **Held** | Tables above |
| Specs > prose when conflict | **Held** | Feature AI report ID alias noted in `00_MAP.md` (FEAT-AI-REPORTING vs FEAT-AI-CONTENT-REPORT); not re-litigated here |

---

## 10. Gap register (actionable)

| ID | Severity | Gap | Required for PASS |
|---|---|---|---|
| P-01 | High | Research Mode / Risky Performance Mode **not consumed** outside `runtime/policy` unit tests | Wire `ProductModePolicy.projectedModes` into diagnostics density, backend selection, placement/risky path; durable RiskAck store; UI consent copy |
| P-02 | High | Dashboard `AllSupportedCapabilityPort` projects every `CapabilityId` as **SUPPORTED** | Replace production wiring with honest map (observability caps CONDITIONAL/SUPPORTED only where software-true; engine caps from `EngineExecuteBinding` / registry) |
| P-03 | High | CapabilityEntry wire lacks full `REQUIRED_EVIDENCE_BINDING` (evidenceId, expiresAt, envelopes) | Extend OpenAPI + DTO + negotiation projection |
| P-04 | Medium | Quality scenarios Q-001… largely `NOT_EXECUTED` | Execute or link automated evidence; do not claim product quality PASS until scenarios run |
| P-05 | Medium | TEXT_GENERATION only exploratory CONDITIONAL; EMBEDDING / STRUCTURED_OUTPUT / TOOL_CALLING not fully implemented | Implement or keep UNKNOWN without CONDITIONAL marketing |
| P-06 | Medium | No dedicated Research/Risky settings UX | Settings sections with irreversible-risk language (personas §1) |
| P-07 | Low | Glossary/persona completeness not machine-checked | Optional traceability from glossary + JTBD to modules in CI |

---

## 11. Scorecard (major sections only)

| Section | Docs ID | Status | Path evidence (anchors) |
|---|---|---|---|
| Core values → modules | PROD-CHARTER | **PARTIAL** | `features/auto-setup`, `features/modelhub`, `runtime/orchestrator`, `features/dashboard`, `WaveAWiring.kt`, `OmniDestinations.kt` |
| Capability model / non-boolean states | PROD-CAPABILITY-MODEL | **PARTIAL** | `CapabilityCatalog.kt`, `CanonicalEnums.kt`, `EngineExecuteBinding.kt`, `EngineRegistry.kt`, `CapabilityEntryDto`; gap: `AllSupportedCapabilityPort` |
| Availability / eligibility | PROD-CAPABILITY-ELIGIBILITY | **PARTIAL** | `specs/capability-availability-matrix.yaml`, `ProductBuildMode.kt`, exploratory setting |
| Product modes | PROD-MODES | **PARTIAL** | Single host+Orchestrator; LAN/Server policies; `ProductModePolicy.kt` L1-only; companion module for risky placement concept |
| Quality & evidence labels | PROD-QUALITY | **PARTIAL** | `EvidenceSemantics.kt`, UI chips, observability tests; `quality-scenarios.yaml` NOT_EXECUTED |
| Boundaries | PROD-BOUNDARIES | **PARTIAL** | Companion gate, qualification honesty, local-first defaults |
| Glossary | PROD-GLOSSARY | **PARTIAL** | Core types in `core/*`, `runtime/*`, `features/benchmark` |
| Personas / JTBD | PROD-PERSONAS | **PARTIAL** | Feature screens + APIs; incomplete first-success / research mode |

---

## 12. Verdict

**PRODUCT / QUALITY / MODES overall: PARTIAL**

The monorepo **materializes** the product model as:

- a **single** Android control plane with feature packs (modes are not forked products at the architecture level);
- a **five-state** capability model and **five evidence labels** in generated contracts and observability;
- **fail-closed** engine qualification defaults (**UNQUALIFIED** / **UNKNOWN**);
- L1–L2 implementations for auto-setup, unified Orchestrator calling, dashboard/diagnostics, developer server, and LAN default-off.

It does **not** yet earn **PASS** because:

1. Research / Risky product modes are **policy-only** (not L2-wired);
2. Dashboard still **advertises all capabilities SUPPORTED** via `AllSupportedCapabilityPort`;
3. Full capability **evidence envelopes** are incomplete on the wire;
4. Quality scenarios and engine cells lack **executed** product evidence (device MEASURED / qualification PASS).

No engine is marked QUALIFIED or SUPPORTED by this audit.

---

*End of `01_product.md`.*
