# 10_traceability — Traceability Matrix Sample

| Field | Value |
|-------|--------|
| **Artifact** | `10_traceability.md` |
| **Audit date (UTC host)** | 2026-08-12 |
| **Docs package (authority)** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo (under audit)** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Out dir** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports` |
| **Method** | Fail-closed path evidence via `list_dir` / `read_file` / `grep` — no memory-only claims |
| **Sample size** | **18** rows (minimum required: 15) |

> Specs under docs package `specs/` take precedence over prose when IDs conflict.  
> Monorepo `specs/` is treated as an implementation copy; authority IDs cited from **docs package** first.  
> Status labels only: `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN`.  
> **PASS** requires concrete path + symbol/quote. No device PASS / Play upload / OEM matrix invented.

---

## 1) Authority inputs read

| Source | Path | Role |
|--------|------|------|
| Traceability matrix | `…Product_Documents\specs\traceability-matrix.yaml` | INV-001…INV-026 → authorities + Q-scenarios |
| Quality scenarios | `…Product_Documents\specs\quality-scenarios.yaml` | Q-001…Q-020 statements; almost all `evidenceStatus: NOT_EXECUTED` |
| Feature ↔ capability map | `…Product_Documents\specs\feature-capability-map.yaml` | FEAT-* → capability IDs |
| Monorepo mirror (spot-check) | `omnillm-android\specs\traceability-matrix.yaml`, `quality-scenarios.yaml` | Same schema present |
| In-repo Q mapping notes | `omnillm-android\docs\architecture\testing.md` | Selected Q → primary unit tests (implementation gates, not product evidence packages) |

### Docs package `traceability-matrix.yaml` (structure)

- Schema version **2**
- **26** invariants (`INV-001`…`INV-026`)
- Each row: `authorities[]` + `qualityScenarios[]` (no code paths in the YAML itself — implementation linkage is the audit object)

### Docs package `quality-scenarios.yaml` (selected)

| ID | Statement (abbrev) | `evidenceStatus` |
|----|--------------------|------------------|
| Q-001 | first model load envelope before side effects | NOT_EXECUTED |
| Q-002 | first request creates session without pre-existing session | NOT_EXECUTED |
| Q-003 | commit reply loss → one durable outcome | NOT_EXECUTED |
| Q-005 | SSE disconnect never reuses unprovable hidden session | NOT_EXECUTED |
| Q-008 | untrusted model/native cannot modify privileged trust state | NOT_EXECUTED |
| Q-014 | exported runtime binder cannot yield admin binder | NOT_EXECUTED |
| Q-015 | native libs / packaging satisfy 16 KB | NOT_EXECUTED |
| Q-016 | engine capability never reused outside envelope | NOT_EXECUTED |
| Q-017 | document authority/type/state/feature/trace zero unresolved links | **PASS** (docs package validator only) |
| Q-019 | AI report without silent content upload | NOT_EXECUTED |
| Q-020 | accessibility + first-success journey testable | NOT_EXECUTED |

> **Implication:** unit/fixture tests in the monorepo are **implementation gates**, not product `evidenceStatus: PASS` packages, unless a release evidence record is present (only Q-017 has one in docs package).

---

## 2) Level legend (L1 / L2 / L3)

| Level | Meaning | Typical evidence |
|-------|---------|------------------|
| **L1** | Module / package exists | Gradle module + source tree |
| **L2** | Wired to control plane | Feature host / WaveA / binder / HTTP gateway injects ports |
| **L3** | Product journey software-complete | UI → feature API → durable mutation path; still **not** device-qualified unless evidence package exists |

Row **Status** aggregates L1–L3 + tests:

| Status | Rule used in this sample |
|--------|--------------------------|
| **PASS** | L1+L2 present with path quotes; automated tests cover the stated contract at unit/fixture level; no conflicting fail-closed gap for that row’s scope |
| **PARTIAL** | Code path found but L3 incomplete, product evidence `NOT_EXECUTED`, wiring gap, or test only partial vs Q-statement |
| **MISSING** | No code path **or** no test path after search (document greps) |
| **N_A** | Out of monorepo Android scope (not used in this sample) |
| **BLOCKED_HUMAN** | Requires human/device/OEM action (not asserted PASS) |

---

## 3) Search log (fail-closed method)

| Probe | Tool / pattern | Outcome |
|-------|----------------|---------|
| First-run / onboarding | grep `FirstRun\|AutoSetup\|Onboarding` `*.{kt,md,yaml}` | `features/auto-setup/**`, `OnboardingScreen.kt`, `MainActivity.defaultDestination` |
| Developer server | grep `DeveloperServer\|FEAT-SERVER` | `features/server/**`, Wave-A, `ServerClientsScreen.kt` |
| Reply-loss recovery | grep `CommitReconcile\|idempotency\|UNCERTAIN` | `CommitReconcileFixtureTest`, ledgers, `RequestRegistry` |
| Trust placement | grep `TrustPlacementPolicy\|CompanionPlacementGate` | pure policy + host gate + companion APK |
| Tests for Q-003/Q-005 | list_dir `runtime/request-registry/src/test`, `core/state/src/test` | present |
| Engine SUPPORTED claim | `specs/engine-qualification-status.yaml` | all cells **UNQUALIFIED** — no QUALIFIED/SUPPORTED |

Empty-finding note: no silent rows invented; every matrix row below has at least one path or an explicit **MISSING** after the probes above.

---

## 4) Sample traceability matrix (≥15 rows)

Columns: **Req/Cap** → **Doc/spec authority** → **Code path (evidence)** → **Test path** → **L1/L2/L3** → **Status**.

### Required focus rows (first-run, developer server, reply-loss, trust)

| # | Requirement / capability | Doc / Q / INV | Code path (path + symbol) | Test path or MISSING | L1 | L2 | L3 | Status |
|---|--------------------------|---------------|---------------------------|----------------------|----|----|----|--------|
| 1 | **First-run journey** — DEVICE_DISCOVERY → RECOMMENDATION → MODEL_ACQUISITION → FIRST_INFERENCE (`FEAT-AUTOSETUP`) | `feature-capability-map.yaml` FEAT-AUTOSETUP; UX journeys; Q-020; INV-024 | `features/auto-setup/.../usecase/AutoSetupService.kt` — `class AutoSetupService`, phases via `SetupJourneyPhases` (`DISCOVERING_DEVICE`…`FIRST_INFERENCE`…`COMPLETED`); `features/auto-setup/.../domain/SetupJourneyState.kt` (`object SetupJourneyPhases`); UI `android/app-ui/.../OnboardingScreen.kt`; control-plane wire `android/runtime-service/.../WaveAWiring.kt` includes `AutoSetupModule.FEATURE_ID` | `features/auto-setup/src/test/.../AutoSetupServiceTest.kt` (`discoverDevice`, `recommend`, `submitFirstInference`, `cancel_firstInferenceRequest`); `RecommendationRankerTest.kt` | PASS | PASS | **PARTIAL** — journey software present, but `MainActivity.defaultDestination()` always returns `Home` even when `!onboardingComplete` (first launch does not force Onboarding); Q-020 `evidenceStatus: NOT_EXECUTED`; engines UNQUALIFIED | **PARTIAL** |
| 2 | **Developer server** — HTTP_INTERFACE + tokens + loopback (`FEAT-SERVER`) | `feature-capability-map.yaml` FEAT-SERVER; INV-013/022 (interface); docs `developer-server.md` | `features/server/.../usecase/DeveloperServerService.kt` — `class DeveloperServerService : DeveloperServerApi`; Wave-A `ServerFeatureModule.FEATURE_ID`; gateway `android/runtime-service/.../http/GatewayLifecycle.kt` + `LoopbackTokenService`; UI `android/app-ui/.../ServerClientsScreen.kt` | `features/server/src/test/.../DeveloperServerServiceTest.kt` (`refresh_readyLoopback_projectsReady`); `CapabilityNegotiationAndCancelTest.kt`; `GatewayLifecycleTest.kt` | PASS | PASS | **PARTIAL** — L2 wired; multi-client device proof / product Q package absent | **PARTIAL** |
| 3 | **Reply-loss recovery** — commit reply loss resolves to one durable outcome | **Q-003**; INV-003/004/007/012; REL-RECOVERY; ADR-004/005 | Pure FSM: `core/state` COMMIT machine used by `CommitReconcileFixtureTest`; durable: `runtime/request-registry/.../RequestRegistry.kt` `fun claim(...)` + `CommandLedger.kt` claim key `(principalId, operationKind, idempotencyKey)`; orchestrator KDoc: “Reply loss: query, never blind replay” in `runtime/orchestrator/.../Orchestrator.kt` | `core/state/src/test/.../CommitReconcileFixtureTest.kt` (RR-001… RR-003: `REPLY_OR_WORKER_LOST` → `COMMITTED` / `ABORTED` / `UNCERTAIN_QUARANTINED`); `runtime/request-registry/src/test/.../CommandConformanceFixturesTest.kt`; `CommitLedgerRecoveryTest.kt`; `data/persistence/.../ClaimOrReturnConformanceTest.kt` | PASS | PASS | **PARTIAL** — unit/fixture + SQL claim shape PASS-level; Q-003 product `evidenceStatus: NOT_EXECUTED` (no kill-at-boundary release package) | **PARTIAL** |
| 4 | **Trust placement** — untrusted accel → companion UID or fail closed | **Q-008**; INV-008/009; SEC-PLACEMENT; ADR-007 | Pure: `engines/api/.../TrustPlacementPolicy.kt` — `object TrustPlacementPolicy`, `fun resolve`, `fun gateProposed`; host gate: `android/runtime-service/.../companion/CompanionPlacementGate.kt` — “Untrusted accelerator without companion ⇒ TRUST_PLACEMENT_REQUIRED”; companion APK: `android/companion-sandbox/.../CompanionSandboxService.kt`, `applicationId com.omnillm.companion` | `engines/api/src/test/.../TrustPlacementPolicyTest.kt` (`gateProposed_externalWithoutCompanion_required`); `android/runtime-service/src/test/.../companion/CompanionPlacementGateTest.kt`; `android/companion-sandbox/src/test/.../CompanionIsolationPolicyTest.kt`, `CompanionTicketValidatorTest.kt` | PASS | **PARTIAL** — policy + gate + companion module present; full product load path using companion for real untrusted GPU not proven as L3 journey | **PARTIAL** — L3 product path partial; Q-008 NOT_EXECUTED (different-UID adversarial product package) | **PARTIAL** |

### Additional sample rows (capabilities / Q / FEAT)

| # | Requirement / capability | Doc / Q / INV | Code path (path + symbol) | Test path or MISSING | L1 | L2 | L3 | Status |
|---|--------------------------|---------------|---------------------------|----------------------|----|----|----|--------|
| 5 | **Idempotent claim-or-return** (client key before send) | INV-012; canonical-types claim key; ADR-004 | `runtime/request-registry/.../RequestRegistry.kt` — `class RequestRegistry`, `fun claim`; `CommandLedger.kt` — “Claim key: `(principalId, operationKind, idempotencyKey)`” | `RequestRegistryTest.kt`; `RequestRegistryConcurrencyTest.kt`; `CommandConformanceFixturesTest.kt` (`fixture_sameKeyDifferentDigest_idempotencyConflict`) | PASS | PASS | PARTIAL (durable SQL path tested; all product clients not exhaustively proven) | **PASS** *(scope: claim ledger contract only)* |
| 6 | **SSE disconnect / no blind re-claim** | **Q-005**; INV-006/007 | `SseDisconnectClaimSemanticsTest` documents contract against `RequestRegistry.claim`; HTTP SSE surfaces under `android/runtime-service/.../transport` | `runtime/request-registry/src/test/.../SseDisconnectClaimSemanticsTest.kt` (`sseDisconnect_doesNotClaimDelivery_queryOnly_q005`); related `ControlPlaneSseStreamRegressionTest.kt` | PASS | PARTIAL | PARTIAL | **PARTIAL** — registry semantics covered; full network-fault product evidence NOT_EXECUTED |
| 7 | **Exported runtime binder ≠ admin** | **Q-014**; INV-001/011 | `android/runtime-service/src/main/AndroidManifest.xml` — `RuntimeBindingService` `android:exported="true"` + permission `BIND_RUNTIME` with comment “Never exposes IOmniAdmin”; `AdminBindingService` `android:exported="false"` | Manifest structural evidence; **no** external-app instrumented “hostile bind” test located in `**/src/test/**` for Q-014 adversarial package | PASS | PASS | PARTIAL | **PARTIAL** — design encoded in manifest; Q-014 external-app manifest test **MISSING** as automated product evidence |
| 8 | **Orchestrator single control path** (plan → claim → reserve → execute) | CORE-ORCHESTRATOR; INV-019; FEAT-ROUTING | `runtime/orchestrator/.../Orchestrator.kt` — `class Orchestrator` KDoc “claim → plan → queue → reserve → commit → prepare → start”; `CandidatePlanner.kt` | `runtime/orchestrator/src/test/.../OrchestratorPipelineTest.kt`; `FallbackPolicyOrchestratorTest.kt`; `RequestLifecycleDriverTest.kt`; `UnknownCapabilityNegativeTest.kt` | PASS | PASS | PARTIAL | **PASS** *(scope: orchestrator unit pipeline)* |
| 9 | **Resource conservation at request terminal** | **Q-004**; INV-005/021 | `runtime/governor/.../ResourceGovernor.kt` — `class ResourceGovernor`; core resource math in `core/resource` | `core/resource/src/test/.../ResourceConservationPropertyTest.kt`; `runtime/governor/src/test/.../ResourceGovernorTest.kt`, `ResourceGovernorConcurrencyTest.kt` | PASS | PASS | PARTIAL | **PASS** *(scope: conservation property / governor unit)* |
| 10 | **Local playground** TEXT_GENERATION / STREAMING / CANCELLATION | FEAT-PLAYGROUND; feature-capability-map | `features/playground/.../usecase/PlaygroundService.kt`; Wave-A `PlaygroundModule.FEATURE_ID`; UI playground screens under `android/app-ui` | `features/playground/src/test/.../PlaygroundCancelTest.kt`; `PlaygroundCapabilityNegotiationTest.kt`; `PlaygroundStreamEventsLocalUiGateTest.kt` | PASS | PASS | PARTIAL | **PARTIAL** — feature + tests; device first-token journey not product-qualified |
| 11 | **Model acquisition / ModelHub** | FEAT-MODELHUB; SAFE_INSTALLATION | `features/modelhub/.../usecase/ModelHubService.kt`; `acquisition/AcquisitionPipeline.kt`; Wave-A `ModelhubModule.FEATURE_ID` | `features/modelhub/src/test/.../AcquisitionPipelineTest.kt`; `ModelHubServiceTest.kt`; `OkHttpArtifactByteSourceTest.kt` | PASS | PASS | PARTIAL | **PARTIAL** |
| 12 | **LAN access default-off / auth policy** | FEAT-LAN; SEC-AUTH-NET | `features/lan/.../usecase/LanAccessService.kt`; `domain/LanAuthPolicy.kt`, `LanServiceLifecyclePolicy.kt` | `features/lan/src/test/.../LanAuthNegativeTest.kt`; `LanPairingNegativeTest.kt`; `LanServiceTest.kt`; `LanPolicyTest.kt` | PASS | PARTIAL (host attach beyond Wave-A — LAN is not in `WAVE_A_FEATURE_IDS`) | PARTIAL | **PARTIAL** — L1+tests strong; Wave-A set is admin/autosetup/modelhub/playground/server/dashboard only |
| 13 | **Multi-model routing / fallback** | FEAT-ROUTING; INV-019; Q-016 (envelope) | `features/routing/` service + policies; orchestrator `CandidatePlanner` + fallback tests | `features/routing/src/test/.../FallbackPolicyRulesTest.kt`; `RoutingNegativeCasesTest.kt`; `Orchestrator` fallback tests | PASS | PARTIAL | PARTIAL | **PARTIAL** — no engine cell SUPPORTED; Q-016 NOT_EXECUTED |
| 14 | **AI content reporting + consent** | FEAT-AI-REPORTING; **Q-019**; INV-023 | `features/ai-content-report/.../usecase/ContentReportService.kt`; `domain/ContentReportPolicy.kt`; durable store ports | `ContentReportNegativeConsentTest.kt`; `ContentReportFullFlowTest.kt`; `ContentReportPolicyTest.kt` (`external_userConfirmed_forbidden`) | PASS | PARTIAL | PARTIAL | **PARTIAL** — policy/consent unit gates; Q-019 product evidence NOT_EXECUTED |
| 15 | **Engine capability envelope / qualification** | **Q-016**; INV-018/019; `engine-qualification-status.yaml` | Registry: `engines/api/.../EngineRegistry.kt` `resolveCapability`; per-engine modules under `engines/*`; **status file** all `qualificationStatus: UNQUALIFIED` | Engine unit tests exist (e.g. llama/litert/mllm packs) but **no** PASS qualification evidence cells; negative matching product package not found as Q-016 evidence | PASS (modules) | PASS (registry) | **MISSING** as SUPPORTED product claim | **PARTIAL** — code present; **do not mark engines QUALIFIED/SUPPORTED** (rule 5) |
| 16 | **Native 16 KB packaging gate** | **Q-015**; INV-026; ANDROID-NATIVE | Root `build.gradle.kts` task `checkNative16kb` → `tools/ci/check_elf_16kb_alignment.py`; engine notes cite 16 KB PASS on some locked artifacts | CI script exists; monorepo claims local assemble 16 KB zip-align in `BUILD_STATUS.md` — **not** treated as product Q-015 evidence package (`evidenceStatus: NOT_EXECUTED` in docs) | PASS | PASS | PARTIAL | **PARTIAL** — tooling present; formal Q-015 release evidence package absent |
| 17 | **Companion sandbox process isolation** | SEC-EXTERNAL-SANDBOX; INV-009; ADR-007 | `android/companion-sandbox/` separate APK; `CompanionSandboxService`, `CompanionCommandGate`, `CompanionFdRegistry` | `CompanionIsolationPolicyTest.kt`; `CompanionTicketValidatorTest.kt` | PASS | PARTIAL | PARTIAL | **PARTIAL** — L1 PASS; adversarial different-UID product suite Q-008 NOT_EXECUTED |
| 18 | **First model load envelope before side effects** | **Q-001**; INV-002/018 | Model load path via `runtime/model-manager` + engine `LoadContracts.kt` / orchestrator plan-before-mutate (ADR-002 “Plan has **no** domain mutation”) | No dedicated `Q-001` fault-injection + peak-measurement suite found under that scenario ID; related: model-manager / engine load unit tests exist but not mapped as Q-001 evidence package | PASS | PASS | PARTIAL | **PARTIAL** — architecture encoded; Q-001 verification (`fault-injection + peak measurement`) **MISSING** as named product evidence |

---

## 5) Required-topic deep notes

### 5.1 First-run journey (row 1)

| Layer | Evidence | Verdict |
|-------|----------|---------|
| Spec | FEAT-AUTOSETUP capabilities: DEVICE_DISCOVERY, RECOMMENDATION, MODEL_ACQUISITION, AUTOMATED_CONFIGURATION, SAFE_INSTALLATION, … | Authority clear |
| L1 | `:features:auto-setup` full tree (`AutoSetupService`, `RecommendationRanker`, `AutomatedConfigurationBuilder`) | **PASS** |
| L2 | `WaveAWiring.WAVE_A_FEATURE_IDS` contains `AutoSetupModule.FEATURE_ID`; ports to Job/Model/Orchestrator | **PASS** |
| L3 UI | `OnboardingScreen` + `AutoSetupViewModel`; but `MainActivity.defaultDestination()` → `Home` even when onboarding incomplete (comment: “Setup is primary CTA” but code does not navigate to Onboarding) | **PARTIAL** |
| Tests | `AutoSetupServiceTest` covers discover/recommend/first-inference claim paths | **PASS** (unit) |
| Product Q-020 | `evidenceStatus: NOT_EXECUTED` | Not product-PASS |

**Overall: PARTIAL** (software journey exists; forced first-run UX + device-qualified first success incomplete).

### 5.2 Developer server (row 2)

| Layer | Evidence | Verdict |
|-------|----------|---------|
| L1 | `DeveloperServerService` / `DeveloperServerApi` / scope catalog | **PASS** |
| L2 | Wave-A server pack; `GatewayLifecycle` + `LoopbackTokenService`; Admin smoke projections | **PASS** |
| L3 | `ServerClientsScreen` + ViewModel; loopback default-off expected via config catalog (not re-audited exhaustively here) | **PARTIAL** |
| Tests | `DeveloperServerServiceTest`, negotiation/cancel tests, gateway lifecycle tests | **PASS** (unit) |

**Overall: PARTIAL** (L1–L2 strong; no multi-client device proof claimed).

### 5.3 Reply-loss recovery (row 3)

| Layer | Evidence | Verdict |
|-------|----------|---------|
| Spec | Q-003; `runtime-recovery-fixtures` RR-*; COMMIT states including `UNCERTAIN_QUARANTINED` | Authority clear |
| Pure FSM | `CommitReconcileFixtureTest` — kill-at-boundary style edges → single terminal | **PASS** (fixture) |
| Durable claim | `RequestRegistry` / `CommandLedger` / SQLDelight claim stores | **PASS** (unit + schema tests) |
| Orchestrator | Explicit reply-loss rule in `Orchestrator` KDoc; optional `CommitLedger` inject | **PASS** L2 design; production inject path is control-plane concern |
| Product Q-003 | `verification: kill-at-boundary`, `evidenceStatus: NOT_EXECUTED` | **PARTIAL** overall |

**Overall: PARTIAL** (implementation gates strong; release evidence package not present).

### 5.4 Trust placement (row 4)

| Layer | Evidence | Verdict |
|-------|----------|---------|
| Pure policy | `TrustPlacementPolicy.resolve` dimensions: authenticity, engine trust, accelerator, companion, phase qualification | **PASS** |
| Host gate | `CompanionPlacementGate` composes policy + `CompanionAvailability` | **PASS** |
| Companion APK | `:android:companion-sandbox` separate `applicationId` | **PASS** L1 |
| Same-UID never security sandbox | Tests assert `allowedInSameUidWorker` false for `TRUST_PLACEMENT_REQUIRED` / external accel | **PASS** (unit) |
| Product Q-008 | different-UID adversarial + product load path | **NOT_EXECUTED** / L3 partial |

**Overall: PARTIAL** (policy quality high; product adversarial evidence missing).

---

## 6) INV → Q → sample code (subset of docs matrix)

| INV | Authorities (docs YAML) | Q | Sample monorepo linkage in this audit |
|-----|-------------------------|---|----------------------------------------|
| INV-001 | ARCH-LOGICAL, ANDROID-SERVICE | Q-014 | Manifest binder split (row 7) |
| INV-003/004 | CORE-ENGINE, CORE-SESSION, REL-RECOVERY | Q-003 | Commit reconcile + ledgers (row 3) |
| INV-005 | CORE-RESOURCE, CORE-SESSION | Q-004 | ResourceGovernor (row 9) |
| INV-006/007 | CORE-INTERFACE, REL-RECOVERY | Q-005 | SSE claim semantics (row 6) |
| INV-008/009 | SEC-PLACEMENT, SEC-EXTERNAL-SANDBOX, SEC-SUPPLY | Q-008 | TrustPlacementPolicy + companion (row 4, 17) |
| INV-012 | CORE-INTERFACE, REL-RECOVERY | Q-003 | claim-or-return (row 5) |
| INV-018/019 | CORE-CAPABILITY, CORE-ORCHESTRATOR, FEAT-ROUTING | Q-001, Q-016 | engines UNQUALIFIED (row 15, 18) |
| INV-023 | FEAT-AI-REPORTING, SEC-DATA-FLOW | Q-019 | Content report consent (row 14) |
| INV-024 | UX-VALIDATION, UX-A11Y-I18N | Q-020 | First-run / a11y journey (row 1) |
| INV-026 | ANDROID-BASELINE, ANDROID-DIST | Q-015 | 16 KB CI gate (row 16) |

---

## 7) Aggregate sample scores

| Status | Count (of 18) | Notes |
|--------|---------------|-------|
| **PASS** | 3 | Rows 5, 8, 9 — scoped unit-contract PASS only |
| **PARTIAL** | 15 | Includes all four required focus rows (1–4) |
| **MISSING** | 0 full rows | Sub-elements called MISSING: Q-014 external-app test; Q-001 named peak-measurement suite; engine SUPPORTED claim |
| **N_A** | 0 | |
| **BLOCKED_HUMAN** | 0 rows | Device/OEM matrices would be BLOCKED_HUMAN if asserted; deliberately not marked PASS |

### L-level rollup (sample)

| Level | Dominant finding |
|-------|------------------|
| **L1** | Nearly all sampled FEAT/runtime modules **present** |
| **L2** | Wave-A wires auto-setup, modelhub, playground, server, dashboard, admin; LAN/routing/tools/report attach partially outside Wave-A set |
| **L3** | Software journeys largely **PARTIAL**; product quality scenarios remain **NOT_EXECUTED** in docs package |

---

## 8) Explicit non-claims (hard rules)

1. **No engine marked QUALIFIED/SUPPORTED** — `specs/engine-qualification-status.yaml` shows `qualificationStatus: UNQUALIFIED` for listed engines; integration notes ≠ qualification.  
2. **No device PASS / Play upload / OEM matrix** invented for first-run or Q-020.  
3. **Unit tests ≠ product evidence packages** for Q-001…Q-016, Q-018…Q-020 (`evidenceStatus: NOT_EXECUTED` in docs `quality-scenarios.yaml`).  
4. **Q-017 PASS** applies to **docs package repository validation**, not monorepo runtime correctness.

---

## 9) Gaps worth closing (traceability-oriented)

| Gap | Impact | Suggested evidence |
|-----|--------|--------------------|
| First launch does not land on Onboarding | L3 first-run | Wire `defaultDestination` or prefs-led gate to `OmniDestination.Onboarding` when incomplete |
| Q-003 product kill-at-boundary package | Q-003 remains NOT_EXECUTED | Device/process-kill harness tied to RR fixtures + durable DB assert |
| Q-008 different-UID adversarial | Trust placement L3 | Instrumented test: companion vs main app privileges |
| Q-014 external-app bind test | Exported binder security | Separate test APK attempting `BIND_RUNTIME` cannot obtain admin |
| Q-001 peak measurement | Load envelope | Fault-injection + peak RSS/GPU before side effects |
| Engine qualification cells | Q-016 / first-success | Non-expired PASS records only — until then keep UNQUALIFIED |

---

## 10) File index (absolute paths cited)

### Docs package
- `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\specs\traceability-matrix.yaml`
- `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\specs\quality-scenarios.yaml`
- `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\specs\feature-capability-map.yaml`

### Monorepo — first-run
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\auto-setup\src\main\kotlin\com\omnillm\features\autosetup\usecase\AutoSetupService.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\auto-setup\src\main\kotlin\com\omnillm\features\autosetup\domain\SetupJourneyState.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\auto-setup\src\test\kotlin\com\omnillm\features\autosetup\AutoSetupServiceTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\app-ui\src\main\kotlin\com\omnillm\ui\screens\OnboardingScreen.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\app-ui\src\main\kotlin\com\omnillm\ui\MainActivity.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\featurehost\WaveAWiring.kt`

### Monorepo — developer server
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\server\src\main\kotlin\com\omnillm\features\server\usecase\DeveloperServerService.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\server\src\test\kotlin\com\omnillm\features\server\DeveloperServerServiceTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\app-ui\src\main\kotlin\com\omnillm\ui\screens\ServerClientsScreen.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\test\kotlin\com\omnillm\android\runtimeservice\http\GatewayLifecycleTest.kt`

### Monorepo — reply-loss
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\core\state\src\test\kotlin\com\omnillm\core\state\CommitReconcileFixtureTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\request-registry\src\main\kotlin\com\omnillm\runtime\requestregistry\RequestRegistry.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\request-registry\src\main\kotlin\com\omnillm\runtime\requestregistry\CommandLedger.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\request-registry\src\test\kotlin\com\omnillm\runtime\requestregistry\CommandConformanceFixturesTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\request-registry\src\test\kotlin\com\omnillm\runtime\requestregistry\SseDisconnectClaimSemanticsTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\orchestrator\src\main\kotlin\com\omnillm\runtime\orchestrator\Orchestrator.kt`

### Monorepo — trust placement
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\engines\api\src\main\kotlin\com\omnillm\engines\api\TrustPlacementPolicy.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\engines\api\src\test\kotlin\com\omnillm\engines\api\TrustPlacementPolicyTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\companion\CompanionPlacementGate.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\test\kotlin\com\omnillm\android\runtimeservice\companion\CompanionPlacementGateTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\companion-sandbox\src\main\kotlin\com\omnillm\companion\CompanionSandboxService.kt`

### Monorepo — other sample rows
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\governor\src\main\kotlin\com\omnillm\runtime\governor\ResourceGovernor.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\core\resource\src\test\kotlin\com\omnillm\core\resource\ResourceConservationPropertyTest.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\playground\src\main\kotlin\com\omnillm\features\playground\usecase\PlaygroundService.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\modelhub\src\main\kotlin\com\omnillm\features\modelhub\usecase\ModelHubService.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\lan\src\main\kotlin\com\omnillm\features\lan\usecase\LanAccessService.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\features\ai-content-report\src\main\kotlin\com\omnillm\features\contentreport\usecase\ContentReportService.kt`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\AndroidManifest.xml`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\specs\engine-qualification-status.yaml`
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\build.gradle.kts` (`checkNative16kb`)
- `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\docs\architecture\testing.md`

---

## 11) Verdict (sample-only)

| Dimension | Status | One-line |
|-----------|--------|----------|
| Docs INV↔Q matrix exists | **PASS** | `traceability-matrix.yaml` + `quality-scenarios.yaml` present and readable |
| Code linkage for sampled FEATs | **PARTIAL** | L1 solid, L2 mostly Wave-A, L3 incomplete for product journeys |
| Test linkage for sampled contracts | **PARTIAL** | Strong unit/fixture gates for recovery, placement, orchestrator, governor; product Q evidence almost all NOT_EXECUTED |
| Required four topics | **PARTIAL** | First-run, developer server, reply-loss, trust placement all code-backed with tests; none full product-PASS |
| Engines SUPPORTED | **MISSING** as claim | All UNQUALIFIED — correctly not elevated |

**Sample audit complete.** Full 26-INV exhaustive code mapping is out of sample scope; rows above are an evidence-backed cross-section ≥15 with the four mandated product topics.
