# OmniLLM Android — Product Readiness Checklist

**As-of:** 2026-08-06  
**Repo:** `omnillm-android/` (edit surface only)  
**Authority (docs):** product package `OmniLLM_Product_Documents` + in-repo `specs/`, `GAP_CLOSEOUT.md`, `BUILD_STATUS.md`, `engines/*/NATIVE.md|README.md`, `docs/70-features` (product), `docs/80-engines` (product).  
**Purpose:** Software-closeable path to **SOFTWARE READY-TO-LAUNCH** vs human/device-only residuals.

> This checklist is an engineering gate inventory. Completing software TODOs does **not** invent device PASS evidence, OEM matrix results, or Play Console approval.

---

## 0. Hard rules (always)

| ID | Rule | Enforcement evidence |
|---|---|---|
| INV-001 | UI never loads native / never writes DB | `tools/ci/check_module_dependency_rules.py`, `check_dependency_edges.py`; `:android:app-ui` deps; `RuntimeControlPlane.attach` process gate |
| ADR-010 | Only runtime control plane is durable writer | `ControlPlaneWriter`, `SingleWriterPolicy`, `RuntimeControlPlane` |
| PRCE | Plan → Reserve → Commit → Execute; Plan has no domain mutation | `:runtime:orchestrator`, engine Plan paths, ADR-002 tests |
| ENG-Q | Never mark engine cells QUALIFIED/SUPPORTED or invent device evidence | `specs/engine-qualification-status.yaml`, `EngineSelectionPolicy`, `capability-matrix.yaml` all UNKNOWN/UNQUALIFIED |
| INV-018 | Fail closed on unknown capability; no silent cross-revision fallback | `CandidatePlanner`, `FeaturePackHost` routing policy tests |
| PLAY | No Play Console upload automation requiring human secrets | `gradle/RELEASE_CHECKLIST.md`, `.github/workflows/release.yml` (build only) |

**Allowed residual (policy):** Runtime may expose **CONDITIONAL exploratory execute** for packaged native when policy allows — capability matrix / qualification YAML must stay honest (`UNKNOWN` / `UNQUALIFIED` without evidence).

---

## 1. Definition of Done (software launch gate)

| # | Criterion | Current status | Evidence |
|---|---|---|---|
| D1 | `assembleRelease` succeeds | **PASS** (observed) | `assemble-run-latest2.txt` → `:android:app-ui:assembleRelease` **BUILD SUCCESSFUL**; companion release also assembled |
| D2 | Unit / host tests green | **PASS** (observed) | `GAP_CLOSEOUT.md` A; `test-verify-gaps.txt` → `./gradlew test` **BUILD SUCCESSFUL** |
| D3a | Claim + commit ledgers durable | **PASS** | `RuntimeControlPlane.attach` → `ControlPlaneDatabase` + `RequestRegistryModule.createWithCommits`; no `InMemoryClaim*` / `createInMemoryWithCommits` in `runtime-service` **main** (`GAP_CLOSEOUT.md` B) |
| D3b | Session ledger durable | **PASS** | `SessionModule.createDurableManager(controlDb.sessions)` in `RuntimeControlPlane.kt` |
| D3c | Jobs durable | **FAIL — software TODO** | `JobManagerModule.createManager()` default `InMemoryJobStore` (`runtime/job-manager/.../JobManagerModule.kt`, plane L318) |
| D3d | Secrets / tokens durable | **FAIL — software TODO** | `InMemorySecretBroker` default: `LoopbackTokenService.kt`, `PolicyModule.createSecurityStack` |
| D4 | llama native load + generate on host/packaged path (shim OK) **or** documented synthetic fixture | **PARTIAL** | Shim: `android/native/src/main/cpp/omnillm_llama.cpp` (synthetic stream); JNI: `JniNativeBackend`; doc: `engines/llama-cpp/NATIVE.md`. **Not** wired into production Orchestrator / AIDL chat execute |
| D5 | All 12 Feature Packs software E2E paths **not permanently** `FailClosedInference` when native present | **FAIL — software TODO** | 12/12 constructed (`FeaturePackHost` + `WaveAWiring`); inference ports still permanent fail-closed (`FailClosedInferenceEngine`, playground/server/tools/routing ports) |
| D6 | UI destinations for LAN / benchmark / tools if missing | **PARTIAL** | LAN tab under `ServerClientsScreen` only; **no** `OmniDestination` for Benchmark or Tools |
| D7 | No fake SUPPORTED | **PASS** | `specs/engine-qualification-status.yaml`; `EnginePackAttachment` asserts `!anySupportedCell`; matrices UNQUALIFIED |

**Software READY-TO-LAUNCH** = D1–D7 all software-closeable items closed (D3c/d, D4 wiring, D5, D6) without elevating qualification cells.

---

## 2. Snapshot vs prior closeouts

| Source | Role |
|---|---|
| `GAP_CLOSEOUT.md` | Gaps 1–3 **done** (12 packs attached; claim/commit SQLite; native packaging path) |
| `GAP_INVENTORY.md` | **Stale** pre-closeout snapshot — do not use as current truth for claim/commit or pack attach |
| `BUILD_STATUS.md` | Module tree, known gaps 4–12, residual risks R-001…R-007 |

---

## 3. Software-closeable TODOs (with path evidence)

Each item is closable **in-repo** without physical devices, OEM matrix, or Play secrets.  
Status: `OPEN` | `PARTIAL` | `DONE`.

### 3.1 Build & verification

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-BUILD-01 | Keep `:android:app-ui:assembleRelease` green on CI/local | **DONE** (re-verify each PR) | `./gradlew :android:app-ui:assembleRelease`; log `assemble-run-latest2.txt` |
| SW-BUILD-02 | Keep `./gradlew test` green | **DONE** (re-verify) | `GAP_CLOSEOUT.md` §A; `tools/ci/local_ci.ps1` |
| SW-BUILD-03 | Root `check` + 16 KB gates on packaged `.so` | **DONE** tooling; re-run when natives change | `./gradlew checkNative16kb`; `tools/ci/check_elf_16kb_alignment.py`; `check_apk_16kb_zipalign.py` |
| SW-BUILD-04 | Refresh stale root `README.md` “skeleton only” blurb → point at `BUILD_STATUS.md` | **DONE** | `README.md` points at `BUILD_STATUS.md`, `PRODUCT_READINESS_CHECKLIST.md`, `AGENTS.md` |
| SW-BUILD-05 | Optional: enable R8 minify only after smoke checklist | **OPEN** (default minify off is correct) | `android/app-ui/build.gradle.kts`; `gradle/RELEASE_CHECKLIST.md` §H; JNI/AIDL keep rules present |
| SW-BUILD-06 | Coherent versionName/versionCode main + companion | **DONE** | `gradle/libs.versions.toml` `appVersion*` / `companionVersion*` → `0.1.0` / `1` |
| SW-BUILD-07 | CI: test + assemble + contract drift + 16kb + dep edges | **DONE** | `.github/workflows/ci.yml`, `release.yml`, `tools/ci/local_ci.{sh,ps1}` |
| SW-BUILD-08 | RELEASE_CHECKLIST human Play steps only | **DONE** | `gradle/RELEASE_CHECKLIST.md` |
| SW-BUILD-09 | detekt baseline | **SKIPPED** (documented) | `tools/ci/README.md` — intentional; not a software launch blocker |

### 3.2 Durability (ADR-010)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-DUR-01 | Claim/commit SQLite in production attach | **DONE** | `android/runtime-service/.../RuntimeControlPlane.kt` L296–314; `data/persistence/.../SqlDelightClaimLedgerStore.kt`, `SqlDelightCommitLedgerStore.kt` |
| SW-DUR-02 | Session SQLite durable manager | **DONE** | `RuntimeControlPlane.kt` L314–317; `Sessions.sq`; `SqlDelightSessionStore` |
| SW-DUR-03 | Durable **jobs** store bound on plane | **OPEN** | Authority SQL: `data/persistence/src/main/resources/db/omnillm-schema.sql` (`jobs`, `job_attempts`, `job_events`). Missing: `.sq` queries under `data/persistence/src/main/sqldelight/`; `SqlDelightJobStore` implementing `JobStore`; wire `JobManagerModule.createManager(store=…)` in `RuntimeControlPlane.attach` instead of default `InMemoryJobStore` (`runtime/job-manager/.../JobStore.kt`, `JobManagerModule.kt` L19) |
| SW-DUR-04 | Durable **secrets / access tokens / pairing** broker | **DONE** | SQLDelight: `AccessTokens.sq`, `PairingChallenges.sq`, `RevocationSubjects.sq`, `SecretBrokerKeys.sq` + `SqlDelightSecretLedgerStore`. Production: `ControlPlaneSecurityFactory` (Keystore-wrapped master + `EncryptedBlobSecretKeyVault` + SQLite HMAC verifiers). Wired: `RuntimeControlPlane.attach` → `securityStack`; `GatewayLifecycle` / `LoopbackTokenService` inject plane `TokenService`. Tests: `SqlDelightSecretLedgerStoreTest`, `DurableSecretBrokerTest`. InMemory defaults remain test-only. |
| SW-DUR-05 | Durable content-report store (secondary) | **OPEN** | `features/ai-content-report/.../ContentReportModule.kt` L55 default `InMemoryContentReportStore`; plane bootstrap does not override |
| SW-DUR-06 | Durable tool proposal ledger (secondary) | **OPEN** | `FeaturePackHost.kt` L135 `InMemoryToolProposalLedger` |
| SW-DUR-07 | Model manager / catalog not process-memory only | **OPEN** (partial product need) | `RuntimeControlPlane.kt` L321 `ModelManagerModule.createInMemoryControlPlane()`; model-store FS ports exist under `data/model-store` but not fully bound as sole catalog authority |
| SW-DUR-08 | SQLDelight subset vs full authority schema | **PARTIAL** | `.sq` covers claim/commit/session/schema only; jobs/tokens absent from sqldelight dir; `applySchema=false` on Android open (`RuntimeControlPlane.kt` L303) — document bootstrap vs `omnillm-schema.sql` migration path |
| SW-DUR-09 | Modelhub display/link ports process-memory | **OPEN** (low severity) | `WaveAWiring.kt` `InMemoryModelDisplayMetadataPort`, `InMemoryAcquisitionLinkStore` |

### 3.3 Engine / native execute path (honest cells)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-ENG-01 | Packaged `libomnillm_llama` CMake/JNI path | **DONE** (shim) | `android/native/src/main/cpp/CMakeLists.txt`, `omnillm_llama.{h,cpp}`, `jni_bridge.cpp`; consumers `:runtime-service`, `:workers` |
| SW-ENG-02 | Document synthetic load+generate fixture path | **DONE** (doc) | `engines/llama-cpp/NATIVE.md` — synthetic stream in shim; `StubNativeBackend` for host unit tests only |
| SW-ENG-03 | Host unit: Plan→Reserve→Commit→Execute on stub | **DONE** | `engines/llama-cpp/src/test/.../LlamaCppEnginePipelineTest.kt` |
| SW-ENG-04 | Production attach: JNI backend when `.so` present; never silent Stub | **DONE** | `EnginePackAttachment.attachAfterReady`; `LlamaCppModule.createEngineWithNativeOrNull` |
| SW-ENG-05 | Keep cells UNQUALIFIED / registry UNKNOWN | **DONE** | `specs/engine-qualification-status.yaml`; each `engines/*/capability-matrix.yaml`; `EngineSelectionPolicy` |
| SW-ENG-06 | Wire Orchestrator inference port to real engine adapter when native present (exploratory CONDITIONAL execute **without** elevating matrix) | **OPEN** | Today: `WaveAWiring.wire` L150–157 always `FailClosedInferenceEngine()` + `CapabilityLookup { UNKNOWN }`. Need: after `ensureEnginePacksAttached`, bind `InferenceEnginePort` from `EnginePackAttachment.llamaCppEngine` under explicit exploratory/CONDITIONAL policy; keep `capability-matrix.yaml` / qualification YAML unchanged |
| SW-ENG-07 | AIDL chat/embed execute not permanent “engine execute path not yet attached” | **OPEN** | `OmniRuntimeFacade.kt` L79–90, L117–128 terminal fail-closed after claim |
| SW-ENG-08 | HTTP gateway orchestrator path uses plane engine when attached | **OPEN** | `ControlPlaneHttpHandler.kt` CAPABILITY_UNSUPPORTED paths; ensure `GatewayLifecycle` receives live `orchestrator` with engine port (plane has orchestrator ref but engine still fail-closed) |
| SW-ENG-09 | Host/instrumented path: load+generate via JNI when fixture/shim available | **OPEN** | Document/run: packaged shim path keys + privileged load ticket; optional host test that loads `JniNativeBackend` when library present (`JniNativeMappingTest` only checks mapping / load attempt). Synthetic fixture = empty/valid keys accepted by `omnillm_llama_load_model` (see C++ args) |
| SW-ENG-10 | Full upstream llama.cpp pin (GGUF real weights) | **OPEN / optional for software launch** | `engines/llama-cpp/UPSTREAM.lock` still `NOT_LOCKED`; `NATIVE.md` swap steps. Shim + SW-ENG-06…09 sufficient for software DoD D4 if synthetic generate works end-to-end |
| SW-ENG-11 | Peer engines remain stub/UNKNOWN (no fake load) | **DONE** (honest) | litert-lm / mlc-llm / mllm / ort-genai stubs; attach registers metadata only |

### 3.4 Feature Pack software E2E (12 packs)

**Done:** all 12 domain services constructed on control plane (`GAP_CLOSEOUT.md` C; `FeaturePackHost.bootstrap` + `WaveAWiring.wire`).

**Blocker for D5:** inference-adjacent packs still hard-wired to fail-closed ports **regardless of native presence**.

| Pack | Module | Plane host | Software E2E unit path | Production inference / live path | Software TODO |
|---|---|---|---|---|---|
| admin | `features/admin` | Wave-A | `AdminFeatureHappyPathTest`, cancel/recover tests | Jobs via `AdminApiService` (jobs in-memory — SW-DUR-03) | SW-FEAT-01 jobs durability UX continuity |
| auto-setup | `features/auto-setup` | Wave-A | module tests | `WaveAWiring` planOnly fail-closed L173–175 | SW-FEAT-02 bind plan to real orchestrator when engine exploratory allowed |
| modelhub | `features/modelhub` | Wave-A | module tests | empty catalog port L191; in-memory display | SW-FEAT-03 catalog/suggested port + durable install jobs |
| playground | `features/playground` | Wave-A | module tests | `PlaygroundInferencePort` permanent CAPABILITY_UNSUPPORTED L204–208 | **SW-FEAT-04** wire to orchestrator/engine when native present |
| server | `features/server` | Wave-A | module tests | `ServerInferencePort` / smoke likely fail-closed (see `WaveAWiring` server ports) | **SW-FEAT-05** smoke + claim path via real engine when allowed |
| dashboard | `features/dashboard` | Wave-A | module tests | projections from governor/capabilities | SW-FEAT-06 live metrics depth (observability still in-memory) |
| lan | `features/lan` | Wave-B | policy + service tests | `ControlPlaneLanHost` + HTTP LAN routes when attached | SW-FEAT-07 durable pairing secrets (SW-DUR-04); UI destination polish (SW-UI-01) |
| benchmark | `features/benchmark` | Wave-B | service tests | jobs-backed runs; no engine measure without SW-ENG-06 | **SW-FEAT-08** measurement path + UI destination (SW-UI-02) |
| diagnostics | `features/diagnostics` | Wave-B | export tests | bundle builder present | SW-FEAT-09 export-preview/encryption UX depth vs product docs |
| routing | `features/routing` | Wave-B | fallback policy tests | `FailClosedRoutingOrchestrator` L126 | **SW-FEAT-10** bind plane `Orchestrator` into `RoutingFeaturePorts` |
| tools | `features/tools` | Wave-B | `ToolNonExecutionAndIdempotencyTest` | `FailClosedToolsInferencePort` L132 | **SW-FEAT-11** structured path via engine when CONDITIONAL; host tool non-execution preserved |
| ai-content-report | `features/ai-content-report` | Wave-B | consent/race tests | API on plane; store in-memory | SW-FEAT-12 durable store (SW-DUR-05) |

#### Critical permanent fail-closed sites (must not remain after native-present software close)

| Site | Path |
|---|---|
| Orchestrator engine | `android/runtime-service/.../featurehost/FailClosedInferenceEngine.kt` |
| Wave-A always injects it | `WaveAWiring.kt` L150–157 |
| Playground chat/embed | `WaveAWiring.kt` L204–208 |
| Routing | `FeaturePackHost.kt` L126 → `FailClosedFeaturePorts.kt` `FailClosedRoutingOrchestrator` |
| Tools inference | `FeaturePackHost.kt` L132 → `FailClosedToolsInferencePort` |
| AIDL chat/embed | `OmniRuntimeFacade.kt` L79–128 |

**Design constraint when closing:** exploratory execute may use CONDITIONAL runtime policy for packaged llama shim; **do not** write PASS evidence or set `qualificationStatus: QUALIFIED` / `registryExposure: SUPPORTED` in YAML.

### 3.5 UI destinations (INV-001: Admin/projections only)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-UI-01 | LAN user surface | **PARTIAL** | LAN tab inside `ServerClientsScreen.kt` (`ServerTab.LAN`) + `LanAccessViewModel`; no dedicated `OmniDestination.Lan`. Product IA may accept tab; if IA requires primary destination, add to `OmniDestinations.kt` / `OmniNavHost.kt` |
| SW-UI-02 | Benchmark destination + screen | **OPEN** | Domain: `features/benchmark/.../BenchmarkViewModel.kt`. Missing: `android/app-ui/.../screens/BenchmarkScreen.kt`, `OmniDestination.Benchmark`, nav rail/more list |
| SW-UI-03 | Tools / structured destination + screen | **OPEN** | Domain: `features/tools`. Missing: Tools screen + `OmniDestination.Tools` |
| SW-UI-04 | Existing primary destinations | **DONE** (shell) | Home, ModelHub, Playground, ServerClients, Dashboard, Settings, Diagnostics, Onboarding, ContentReport — `OmniDestinations.kt`, screens under `app-ui/.../screens/` |
| SW-UI-05 | Live Admin binder depth per screen | **PARTIAL** | Screens exist; depth varies (`BUILD_STATUS.md` §8 #9). Deepen projections without UI DB/native |

### 3.6 Transport / process topology (software)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-XPORT-01 | Loopback HTTP gateway + OpenAPI inventory | **DONE** (scaffold) | `interfaces/http`, `GatewayLifecycle`, `ControlPlaneHttpHandler` |
| SW-XPORT-02 | AIDL 44 interfaces + binder facades | **DONE** (scaffold) | `interfaces/aidl`, `OmniRuntimeFacade`, `OmniAdminFacade` |
| SW-XPORT-03 | Feature HTTP/AIDL parity for wave-B | **DONE** (launch-critical) | HTTP + AIDL content-report/models wired; delivery in `core/errors/TransportDeliveryGuarantee`; tests: `TransportParityTest`, `LaunchCriticalHttpSurfaceTest`, `StreamDeliveryAckCreditTest`, `OpenApiRouteSmokeTest` |
| SW-XPORT-04 | Multi-process E2E automated on host | **PARTIAL** | Worker/parser/companion unit policy tests; thin `RuntimeServiceInstrumentedSmokeTest.kt` only |

### 3.7 Honesty / anti-fraud gates (must stay green)

| ID | Check | Status | Path |
|---|---|---|---|
| SW-HONEST-01 | No SUPPORTED without PASS | **DONE** | `EngineSelectionPolicy`, attach tests |
| SW-HONEST-02 | No silent cross-revision fallback | **DONE** | routing tests + `CandidatePlanner` |
| SW-HONEST-03 | Capability matrix runtimeDefault UNKNOWN | **DONE** | all `engines/*/capability-matrix.yaml` |
| SW-HONEST-04 | Fail closed when native missing (no Stub in prod) | **DONE** | `EnginePackAttachment` production path |

---

## 4. OUT_OF_SCOPE (human / device / ops only)

Do **not** treat these as software blockers for monorepo “software ready.” Record owners; never invent PASS.

| ID | Item | Why human-only | Related paths / docs |
|---|---|---|---|
| OO-01 | Physical device multi-process E2E | Needs real APK install + process isolation validation | `GAP_CLOSEOUT.md` F gap #4; thin androidTest |
| OO-02 | Engine qualification PASS cells / OEM matrix | Device fingerprints, thermal, driver evidence; time-bounded envelopes | `specs/engine-qualification-status.yaml`; product `docs/80-engines/qualification-status-and-evidence.md` |
| OO-03 | Mark any engine QUALIFIED / SUPPORTED | Evidence pack only; inventing PASS forbidden | HARD RULE 4 |
| OO-04 | Full upstream GGUF model quality / accuracy claims | Real models + human eval | R-003 residual |
| OO-05 | Play Console upload / review / listing | Secrets + manual questionnaires | `gradle/RELEASE_CHECKLIST.md` §J–L; gap #5 |
| OO-06 | Play App Signing / keystore ceremony | Human secrets | `release.yml` optional secrets; never commit keystores |
| OO-07 | FGS / AI content / Data Safety Console forms | Manual policy forms + demo video | RELEASE_CHECKLIST §D–F; `DATA_SAFETY_INVENTORY.md` |
| OO-08 | Privacy policy URL / legal copy live | Legal-owned | RELEASE_CHECKLIST §F/J |
| OO-09 | Companion dual-APK same-signer Play multi-package validation | Distribution ops | `companion-sandbox/PACKAGING.md` |
| OO-10 | 16 KB system image field validation with full natives | Device image | ANDROID-16KB |
| OO-11 | Catalog root multi-sig ceremony / production trust roots | Ops / security | R-005; model-manager supply-chain hooks only code-level |
| OO-12 | OEM driver/kernel residual risk closure | Unclosable by design | GOV-RISKS R-002 |
| OO-13 | detekt / org static analysis suite | Explicitly skipped (documented); add only with org mandate | `tools/ci/README.md` |
| OO-14 | Crash recovery fixtures **on device** after durable claim/commit | Device power-kill suite | `specs/runtime-recovery-fixtures.yaml` (host partial) |

---

## 5. Suggested software close order (launch path)

Respects ADR-010, INV-001, Plan purity, fail-closed, no fake SUPPORTED.

1. **SW-DUR-03** jobs SQLite + plane bind  
2. **SW-DUR-04** secrets/tokens SQLite + `LoopbackTokenService` / security stack  
3. **SW-ENG-06 + SW-ENG-07 + SW-ENG-08** exploratory CONDITIONAL execute path for packaged llama shim (matrix stays UNKNOWN/UNQUALIFIED)  
4. **SW-FEAT-04 / 05 / 10 / 11** playground, server, routing, tools ports → live orchestrator/engine  
5. **SW-UI-02 / SW-UI-03** Benchmark + Tools destinations (LAN polish SW-UI-01 if IA requires)  
6. **SW-DUR-05 / 06 / 07** secondary durability (content-report, tool ledger, model manager)  
7. **SW-ENG-09** host/packaged load+generate proof (synthetic fixture documented in NATIVE.md)  
8. ~~**SW-BUILD-04** README status refresh~~ (done)  
9. Re-run: `./gradlew test` + `:android:app-ui:assembleRelease` + `checkNative16kb`

**Stop conditions for “software ready”:** D1–D7 software items closed; qualification YAML still all UNQUALIFIED; no Play upload automation added.

---

## 6. Per-DoD residual after software close (expected)

Even when SW-* above are DONE:

| Residual | Category |
|---|---|
| All engine cells UNQUALIFIED / UNKNOWN | Honest default until OO-02 |
| Shim ≠ full llama.cpp GGUF | SW-ENG-10 optional / OO-04 |
| R-001…R-007 product residual risks | Design-accepted (`BUILD_STATUS.md` §9) |
| Instrumentation thin | OO-01 |
| Play publication | OO-05…OO-08 |

---

## 7. Verification commands (software)

```powershell
# From repo root
.\gradlew.bat test
.\gradlew.bat checkContractDrift
.\gradlew.bat checkModuleDependencyRules
.\gradlew.bat checkDependencyEdges
.\gradlew.bat checkNative16kb
.\gradlew.bat :android:app-ui:assembleRelease
.\gradlew.bat :android:companion-sandbox:assembleRelease

# Optional full local CI
.\tools\ci\local_ci.ps1
```

Grep honesty checks:

```text
# Production claim/commit must stay non-InMemory
rg "InMemoryClaimLedgerStore|createInMemoryWithCommits" android/runtime-service/src/main

# No SUPPORTED elevation in qualification authority
rg "qualificationStatus: QUALIFIED|registryExposure: SUPPORTED" engines specs/engine-qualification-status.yaml

# Fail-closed sites that should disappear for D5 when native path wired
rg "FailClosedInferenceEngine|engine execute path not yet attached|playground engine not attached" android/runtime-service
```

---

## 8. Feature × software readiness matrix (quick)

| Feature | Module | Plane | Unit tests | UI surface | Inference/E2E software | Durability residual |
|---|---|---|---|---|---|---|
| FEAT-ADMIN | `:features:admin` | Yes | Yes | Home/Settings/Jobs | Jobs lifecycle OK | jobs InMemory |
| FEAT-AUTOSETUP | `:features:auto-setup` | Yes | Yes | Onboarding | plan fail-closed | — |
| FEAT-MODELHUB | `:features:modelhub` | Yes | Yes | ModelHubScreen | catalog empty / jobs mem | display InMemory |
| FEAT-PLAYGROUND | `:features:playground` | Yes | Yes | PlaygroundScreen | **permanent fail-closed** | — |
| FEAT-SERVER | `:features:server` | Yes | Yes | ServerClientsScreen | smoke/inference closed | tokens InMemory |
| FEAT-LAN | `:features:lan` | Yes | Yes | Server tab LAN | policy E2E OK | pairing secrets mem |
| FEAT-DASHBOARD | `:features:dashboard` | Yes | Yes | DashboardScreen | projection OK | metrics mem |
| FEAT-BENCHMARK | `:features:benchmark` | Yes | Yes | **no destination** | engine measure closed | jobs mem |
| FEAT-DIAGNOSTICS | `:features:diagnostics` | Yes | Yes | DiagnosticsScreen | export software path | — |
| FEAT-ROUTING | `:features:routing` | Yes | Yes | **no destination** | orchestrator fail-closed | — |
| FEAT-TOOLS | `:features:tools` | Yes | Yes | **no destination** | inference fail-closed | ledger mem |
| FEAT-AI-CONTENT-REPORT | `:features:ai-content-report` | Yes | Yes | ContentReportScreen | draft/queue software | store mem |

---

## 9. Files / surfaces to re-audit when refreshing this checklist

- `GAP_CLOSEOUT.md`, `BUILD_STATUS.md` (this file’s primary engineering authority)
- `android/runtime-service/.../RuntimeControlPlane.kt`, `WaveAWiring.kt`, `FeaturePackHost.kt`, `EnginePackAttachment.kt`
- `android/app-ui/.../navigation/OmniDestinations.kt`, `screens/*`
- `data/persistence` `.sq` + `omnillm-schema.sql`
- `runtime/job-manager`, `runtime/policy/.../SecretBroker.kt`
- `engines/*/capability-matrix.yaml`, `specs/engine-qualification-status.yaml`
- `engines/llama-cpp/NATIVE.md`, `android/native/src/main/cpp/*`
- Product `docs/70-features/*`, `docs/80-engines/*`, `governance/risk-register.md`

---

## 10. Packaging / readiness residual

| Action | Result |
|---|---|
| Packaging pass | README, version `0.1.0`/`1`, ProGuard JNI/AIDL keeps, CI harden, RELEASE_CHECKLIST human Play steps, detekt skip documented |
| assembleRelease | `:android:app-ui` + `:android:companion-sandbox` **BUILD SUCCESSFUL** (unsigned) |
| Gates re-run | `checkContractDrift` + `checkDependencyEdges` + `checkNative16kb` **OK** |
| Residual human-only | §4 OUT_OF_SCOPE (OO-01…OO-14) — device, OEM, Play Console |
| Top software residuals (non-packaging) | See SW-DUR-03, SW-ENG-06…, SW-FEAT-*, SW-UI-* above — not invent PASS evidence |

---

*End of PRODUCT_READINESS_CHECKLIST.md — software gate inventory only; no fake evidence.*
