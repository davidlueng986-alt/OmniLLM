# OmniLLM Android — Product Readiness Checklist

**As-of:** 2026-08-09 (Stage 4 launch-readiness refresh)  
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
| D2 | Unit / host tests green | **PASS** (observed) | `GAP_CLOSEOUT.md` A; `test-verify-gaps.txt` → `./gradlew test` **BUILD SUCCESSFUL** (302 tasks, 2026-08-09) |
| D3a | Claim + commit ledgers durable | **PASS** | `RuntimeControlPlane.attach` → `ControlPlaneDatabase` + `RequestRegistryModule.createWithCommits`; no `InMemoryClaim*` / `createInMemoryWithCommits` in `runtime-service` **main** (`GAP_CLOSEOUT.md` B) |
| D3b | Session ledger durable | **PASS** | `SessionModule.createDurableManager(controlDb.sessions)` in `RuntimeControlPlane.kt` |
| D3c | Jobs durable | **PASS** | `JobManagerModule.createDurableManager(ports=controlDb.jobs)` (`RuntimeControlPlane.kt` L357) + `Jobs.sq`/`SqlDelightJobLedgerStore` (wired in baseline tree `dbf6f33`/`4a5bebe`) |
| D3d | Secrets / tokens durable | **PASS** | `ControlPlaneSecurityFactory.createSecurityStack` (Keystore vault + `AccessTokens.sq`/`PairingChallenges.sq`/`SecretBrokerKeys.sq`/`RevocationSubjects.sq` + `SqlDelightSecretLedgerStore`); `LoopbackTokenService` injects plane `TokenService` |
| D4 | llama native load + generate on host/packaged path (shim OK) **or** documented synthetic fixture | **PASS** (real path) | `libomnillm_llama.so` links **vendored llama.cpp b9999** (`android/native/src/main/cpp/third_party/llama.cpp`); `LlamaCppInferenceEngineAdapter` + `RuntimeGgufModelSourceResolver` wired via `EngineExecuteBinding`; `RealLlamaUpstreamInstrumentedTest` PASS on emulator (gemma-3-270m-Q8_0 → 12 tokens, `upstreamLinked=true`); EXPERIMENTAL_FIXTURE loop still present for synthetic path |
| D5 | All 12 Feature Packs software E2E paths **not permanently** `FailClosedInference` when native present | **PASS** | `WaveAWiring` + `FeaturePackHost` bind `engine = binding.inferenceEngine` (`DelegatingInferenceEngine`, fail-closed only when unbound); playground/server/tools/routing ports route through Orchestrator engine port; `FailClosedInferenceEngine` remains only as unbound fallback |
| D6 | UI destinations for LAN / benchmark / tools if missing | **PARTIAL** | `OmniDestination.Lan` + `Benchmark` + `Routing` + screens exist (`dbf6f33` tree); **`OmniDestination.Tools` still missing** (SW-UI-03 OPEN) |
| D7 | No fake SUPPORTED | **PASS** | `specs/engine-qualification-status.yaml`; `EnginePackAttachment` asserts `!anySupportedCell`; matrices UNQUALIFIED; dev-mode projections CONDITIONAL (COR-10) |

**Software READY-TO-LAUNCH** = D1–D7 all software-closeable items closed (SW-UI-03, SW-ENG-09 on-device proof) without elevating qualification cells.

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
| SW-BUILD-03 | Root `check` (hermetic) + 16 KB gates on packaged `.so` (post-assemble) | **DONE** tooling; re-run when natives change | Hermetic `check` = contract/AIDL drift + unit tests (via `test`) + dep edges — missing `.so`/APK must NOT make it red; `./gradlew checkNative16kb`; `tools/ci/check_elf_16kb_alignment.py`; `check_apk_16kb_zipalign.py` run after assemble |
| SW-BUILD-04 | Refresh stale root `README.md` “skeleton only” blurb → point at `BUILD_STATUS.md` | **DONE** | `README.md` points at `BUILD_STATUS.md`, `PRODUCT_READINESS_CHECKLIST.md`, `AGENTS.md` |
| SW-BUILD-05 | Optional: enable R8 minify only after smoke checklist | **OPEN** (default minify off is correct) | `android/app-ui/build.gradle.kts`; `gradle/RELEASE_CHECKLIST.md` §H; JNI/AIDL keep rules present |
| SW-BUILD-06 | Coherent versionName/versionCode main + companion | **DONE** | `gradle/libs.versions.toml` `appVersion*` / `companionVersion*` → `0.2.0` / `2` (`a19d535`) |
| SW-BUILD-07 | CI: test + assemble + contract drift + 16kb + dep edges | **DONE** | `.github/workflows/ci.yml`, `release.yml`, `tools/ci/local_ci.{sh,ps1}` |
| SW-BUILD-08 | RELEASE_CHECKLIST human Play steps only | **DONE** | `gradle/RELEASE_CHECKLIST.md` |
| SW-BUILD-09 | detekt baseline | **SKIPPED** (documented) | `tools/ci/README.md` — intentional; not a software launch blocker |

### 3.2 Durability (ADR-010)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-DUR-01 | Claim/commit SQLite in production attach | **DONE** | `android/runtime-service/.../RuntimeControlPlane.kt`; `data/persistence/.../SqlDelightClaimLedgerStore.kt`, `SqlDelightCommitLedgerStore.kt` |
| SW-DUR-02 | Session SQLite durable manager | **DONE** | `RuntimeControlPlane.kt`; `Sessions.sq`; `SqlDelightSessionStore` |
| SW-DUR-03 | Durable **jobs** store bound on plane | **CLOSED** (baseline `dbf6f33`/`4a5bebe`) | `Jobs.sq`/`JobAttempts.sq`/`JobEvents.sq` under `data/persistence/src/main/sqldelight/`; `SqlDelightJobLedgerStore`; `RuntimeControlPlane.attach` → `JobManagerModule.createDurableManager(ports=controlDb.jobs)` (L357) + `jobs.reconcileAfterRestart()`; no `InMemoryJobStore` in production main |
| SW-DUR-04 | Durable **secrets / access tokens / pairing** broker | **DONE** | SQLDelight: `AccessTokens.sq`, `PairingChallenges.sq`, `RevocationSubjects.sq`, `SecretBrokerKeys.sq` + `SqlDelightSecretLedgerStore`. Production: `ControlPlaneSecurityFactory` (Keystore-wrapped master + `EncryptedBlobSecretKeyVault` + SQLite HMAC verifiers). Wired: `RuntimeControlPlane.attach` → `securityStack`; `GatewayLifecycle` / `LoopbackTokenService` inject plane `TokenService`. Tests: `SqlDelightSecretLedgerStoreTest`, `DurableSecretBrokerTest`. InMemory defaults remain test-only. |
| SW-DUR-05 | Durable content-report store (secondary) | **CLOSED** (baseline `dbf6f33`/`4a5bebe`) | `ContentReports.sq` + `SqlDelightContentReportStore`; `FeaturePackHost` → `ContentReportModule.createDurableApi(...)`; `payload_created_at`/`active_grant_id`/`receipt_*` columns in authority SQL (API-40..44 `1c66aff`) |
| SW-DUR-06 | Durable tool proposal ledger (secondary) | **CLOSED** (baseline `dbf6f33`/`4a5bebe`) | `ToolProposals.sq`/`ToolResultClaims.sq` + `SqlDelightToolProposalStore`; `FeaturePackHost` → `ToolsFeatureModule.createDurableApi(ledger=toolProposalLedger)`; hermetic tests omit for `InMemoryToolProposalLedger` |
| SW-DUR-07 | Model manager / catalog not process-memory only | **CLOSED** (baseline `dbf6f33`/`4a5bebe`) | `RuntimeControlPlane.attach` → `ModelManagerModule.createDurableControlPlane(installationPorts=controlDb.installations, leasePorts=controlDb.revisionLeases, modelStore=...)`; `Installations.sq`/`RevisionLeases.sq` + `SqlDelightInstallationStore`/`SqlDelightRevisionLeaseStore` |
| SW-DUR-08 | SQLDelight subset vs full authority schema | **PARTIAL** | **28/51** tables + `client_registration_epoch` singleton IMPLEMENTED (was 26/51; C-08b/c `e661a22`/`e8b84ae`/`cfcbdd1` added `assets` + `client_registrations`); remaining 23 marked `-- STATUS: PLANNED (not yet implemented)` in `specs/database/omnillm-schema.sql`; `applySchema=false` on Android open (`RuntimeControlPlane.kt` L342) — document bootstrap vs `omnillm-schema.sql` migration path |
| SW-DUR-09 | Modelhub display/link ports process-memory | **OPEN** (low severity) | `WaveAWiring.kt` `InMemoryModelDisplayMetadataPort`, `InMemoryAcquisitionLinkStore` |

### 3.3 Engine / native execute path (honest cells)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-ENG-01 | Packaged `libomnillm_llama` CMake/JNI path | **DONE** | `android/native/src/main/cpp/CMakeLists.txt`, `omnillm_llama.{h,cpp}`, `jni_bridge.cpp`; consumers `:runtime-service`, `:workers` |
| SW-ENG-02 | Document synthetic load+generate fixture path | **DONE** (doc) | `engines/llama-cpp/NATIVE.md` — synthetic stream in shim (EXPERIMENTAL_FIXTURE); `StubNativeBackend` for host unit tests only |
| SW-ENG-03 | Host unit: Plan→Reserve→Commit→Execute on stub | **DONE** | `engines/llama-cpp/src/test/.../LlamaCppEnginePipelineTest.kt` |
| SW-ENG-04 | Production attach: JNI backend when `.so` present; never silent Stub | **DONE** | `EnginePackAttachment.attachAfterReady`; `LlamaCppModule.createEngineWithNativeOrNull` |
| SW-ENG-05 | Keep cells UNQUALIFIED / registry UNKNOWN | **DONE** | `specs/engine-qualification-status.yaml`; each `engines/*/capability-matrix.yaml`; `EngineSelectionPolicy` |
| SW-ENG-06 | Wire Orchestrator inference port to real engine adapter when native present (exploratory CONDITIONAL execute **without** elevating matrix) | **CLOSED** (baseline `dbf6f33`/`4a5bebe`) | `WaveAWiring` binds `engine = deps.inferenceEngineOverride ?: binding.inferenceEngine` (`EngineExecuteBinding` → `DelegatingInferenceEngine` → `LlamaCppInferenceEngineAdapter`); real GGUF path via `RuntimeGgufModelSourceResolver` (READY install → `openReadOnly` + INV-010 re-verify → in-process generate); capability projection stays CONDITIONAL w/ `development_ship_mode` condition (COR-10); matrices UNCHANGED |
| SW-ENG-07 | AIDL chat/embed execute not permanent “engine execute path not yet attached” | **CLOSED** (COR-03/04 `d0f1734`/`fdb4f91`, baseline wiring) | `OmniRuntimeFacade` chat streams through Orchestrator Plan→Reserve→Commit→Execute (sole claimer); SSE chat never throws inside the flow (honest terminal events); aggregated chat text real |
| SW-ENG-08 | HTTP gateway orchestrator path uses plane engine when attached | **CLOSED** (baseline + SSE `d0f1734`) | `GatewayLifecycle` receives plane `orchestrator` + engine port; `/v1/chat/completions?stream` SSE framing (role/delta/finish_reason/[DONE]); CAPABILITY_UNSUPPORTED only when unbound |
| SW-ENG-09 | Host/instrumented path: load+generate via JNI when fixture/shim available | **DONE** | `RealLlamaUpstreamInstrumentedTest` — connected test PASS on Pixel_7 AVD: gemma-3-270m-Q8_0.gguf (301MB) → `promptTokens=2 completionTokens=12 stop=COMPLETED`, `upstreamLinked=true` (logcat `OmniNativeE2E`) |
| SW-ENG-10 | Full upstream llama.cpp pin (GGUF real weights) | **DONE** (lock) | `engines/llama-cpp/UPSTREAM.lock` **LOCKED**: b9999/47c7869, source/toolchain/artifact digests, 16 KB PASS; vendored under `android/native/src/main/cpp/third_party/llama.cpp` (BLD-01 `105856a`) |
| SW-ENG-11 | Peer engines remain stub/UNKNOWN (no fake load) | **CLOSED — upgraded to real, still honest** | litert/ort/mllm/mlc now **real runtime backends** (`27115ff`/`2da721d`/`080dae0`/`4f98347`) with `integrationStatus` in `specs/engine-qualification-status.yaml`; locks LOCKED except mlc (pin); all remain UNQUALIFIED — device evidence is the only missing piece |

### 3.4 Feature Pack software E2E (12 packs)

**Done:** all 12 domain services constructed on control plane (`GAP_CLOSEOUT.md` C; `FeaturePackHost.bootstrap` + `WaveAWiring.wire`); inference-adjacent packs now route through the plane engine port (`EngineExecuteBinding` → `DelegatingInferenceEngine`), fail-closed only when unbound.

| Pack | Module | Plane host | Software E2E unit path | Production inference / live path | Software TODO |
|---|---|---|---|---|---|
| admin | `features/admin` | Wave-A | `AdminFeatureHappyPathTest`, cancel/recover tests | Jobs via `AdminApiService` (jobs durable) | SW-FEAT-01 jobs durability UX continuity → **CLOSED** (durable) |
| auto-setup | `features/auto-setup` | Wave-A | module tests | plan routed via plane engine when bound (dev) | SW-FEAT-02 → **CLOSED** (binding wired) |
| modelhub | `features/modelhub` | Wave-A | module tests | durable install jobs + LOAD/UNLOAD via `ModelLoadRuntimePort` | SW-FEAT-03 catalog/suggested port + display metadata (in-memory) **PARTIAL** |
| playground | `features/playground` | Wave-A | module tests | chat/embed via Orchestrator engine port (real GGUF path on llama) | **SW-FEAT-04** → **CLOSED** (wired; device stability pending) |
| server | `features/server` | Wave-A | module tests | smoke + SSE chat via plane engine when bound | **SW-FEAT-05** → **CLOSED** (SSE stream live, COR-03) |
| dashboard | `features/dashboard` | Wave-A | module tests | projections from governor/capabilities | SW-FEAT-06 live metrics depth (observability in-memory) **OPEN** |
| lan | `features/lan` | Wave-B | policy + service tests | `ControlPlaneLanHost` + HTTP LAN routes; dedicated `Lan` destination | SW-FEAT-07 durable pairing → **CLOSED** (SW-DUR-04) |
| benchmark | `features/benchmark` | Wave-B | service tests | jobs-backed runs; dedicated `Benchmark` destination + screen | **SW-FEAT-08** measurement on real engines → **PARTIAL** (needs device qualification) |
| diagnostics | `features/diagnostics` | Wave-B | export tests | bundle builder present | SW-FEAT-09 export-preview/encryption UX depth **OPEN** |
| routing | `features/routing` | Wave-B | fallback policy tests | plane Orchestrator bound into `RoutingFeaturePorts`; dedicated `Routing` destination | **SW-FEAT-10** → **CLOSED** (bound) |
| tools | `features/tools` | Wave-B | `ToolNonExecutionAndIdempotencyTest` | structured path via engine when CONDITIONAL; durable proposal/claim ledger | **SW-FEAT-11** → **CLOSED** (ledger + adapter); host tool non-execution preserved |
| ai-content-report | `features/ai-content-report` | Wave-B | consent/race tests | API on plane; store durable | SW-FEAT-12 durable store → **CLOSED** (SW-DUR-05) |

#### Fail-closed sites now (delegating, unbound-only — no permanent hard-wiring)

| Site | Path | Status |
|---|---|---|
| Orchestrator engine | `featurehost/DelegatingInferenceEngine.kt` + `FailClosedInferenceEngine.kt` | Delegates to `EngineExecuteBinding.inferenceEngine` when bound; fail-closed fallback only |
| Wave-A engine port | `WaveAWiring.kt` `engine = deps.inferenceEngineOverride ?: binding.inferenceEngine` | Real port wired (llama GGUF verified) |
| Playground chat/embed | `WaveAWiring.kt` playground ports | Plane engine port; fail-closed when unbound |
| Routing | `FeaturePackHost.kt` `FailClosedRoutingOrchestrator` | `binding = waveA.engineExecute` — live when bound |
| Tools inference | `FeaturePackHost.kt` `FailClosedToolsInferencePort` | live when bound |
| AIDL chat/embed | `OmniRuntimeFacade.kt` | Orchestrator Plan→Reserve→Commit→Execute stream (COR-03/04) |

**Design constraint when closing:** exploratory execute uses CONDITIONAL runtime policy for the packaged llama shim; **do not** write PASS evidence or set `qualificationStatus: QUALIFIED` / `registryExposure: SUPPORTED` in YAML.

### 3.5 UI destinations (INV-001: Admin/projections only)

| ID | TODO | Status | Path evidence / action |
|---|---|---|---|
| SW-UI-01 | LAN user surface | **DONE** | Dedicated `OmniDestination.Lan` + screen in `OmniNavHost` (nav label/icon wired) |
| SW-UI-02 | Benchmark destination + screen | **DONE** | `OmniDestination.Benchmark` + `BenchmarkScreen` wired into nav |
| SW-UI-03 | Tools / structured destination + screen | **OPEN** | Domain: `features/tools`. Missing: Tools screen + `OmniDestination.Tools` (embedded in Playground / HTTP today) |
| SW-UI-04 | Existing primary destinations | **DONE** (shell) | Home, ModelHub, Playground, ServerClients, Lan, Dashboard, Settings, Diagnostics, Onboarding, ContentReport, Routing, Benchmark — `OmniDestinations.kt`, screens under `app-ui/.../screens/` |
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

Done since prior inventory (`PRODUCT_READINESS_CHECKLIST` 2026-08-06):

1. ~~**SW-DUR-03** jobs SQLite + plane bind~~ (closed, baseline `dbf6f33`/`4a5bebe`)  
2. ~~**SW-DUR-04** secrets/tokens SQLite + security stack~~ (closed)  
3. ~~**SW-ENG-06 + SW-ENG-07 + SW-ENG-08** exploratory CONDITIONAL execute path~~ (closed — real GGUF path + SSE chat; matrix stays UNKNOWN/UNQUALIFIED)  
4. ~~**SW-FEAT-04 / 05 / 10 / 11** playground, server, routing, tools ports → live orchestrator/engine~~ (closed)  
5. ~~**SW-UI-01 / SW-UI-02** LAN + Benchmark destinations~~ (closed; Routing also shipped)  
6. ~~**SW-DUR-05 / 06 / 07** secondary durability (content-report, tool ledger, model manager)~~ (closed)  
7. ~~**SW-ENG-09** host/packaged load+generate proof~~ (closed: emulator connected test PASS)  
8. ~~**SW-ENG-10** llama upstream pin~~ (closed: LOCKED b9999)  

Remaining software-closeable (this wave + later):

9. **SW-UI-03** Tools destination + screen.  
10. **SW-FEAT-03** modelhub catalog/suggested port + display metadata durable.  
11. **SW-FEAT-06/09** live metrics depth + diagnostics export-preview UX.  
12. Re-run: `./gradlew test` + `:android:app-ui:assembleRelease` + `checkNative16kb` per PR.

**Device/human (not software-closeable):** engine qualification PASS cells on device matrix (Q1), crash-recovery on device, Play ops (OO-05…OO-11).

**Stop conditions for “software ready”:** D1–D7 software items closed (SW-UI-03 last); qualification YAML still all UNQUALIFIED; no Play upload automation added.

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
| FEAT-ADMIN | `:features:admin` | Yes | Yes | Home/Settings/Jobs | Jobs lifecycle OK | — |
| FEAT-AUTOSETUP | `:features:auto-setup` | Yes | Yes | Onboarding | plane engine when bound | — |
| FEAT-MODELHUB | `:features:modelhub` | Yes | Yes | ModelHubScreen | LOAD/UNLOAD durable | display metadata InMemory |
| FEAT-PLAYGROUND | `:features:playground` | Yes | Yes | PlaygroundScreen | real GGUF path (llama) | — |
| FEAT-SERVER | `:features:server` | Yes | Yes | ServerClientsScreen | SSE chat stream live | — |
| FEAT-LAN | `:features:lan` | Yes | Yes | Lan destination | policy E2E OK | — |
| FEAT-DASHBOARD | `:features:dashboard` | Yes | Yes | DashboardScreen | projection OK | metrics mem |
| FEAT-BENCHMARK | `:features:benchmark` | Yes | Yes | Benchmark destination | measure needs device evidence | — |
| FEAT-DIAGNOSTICS | `:features:diagnostics` | Yes | Yes | DiagnosticsScreen | export software path | — |
| FEAT-ROUTING | `:features:routing` | Yes | Yes | Routing destination | orchestrator bound | — |
| FEAT-TOOLS | `:features:tools` | Yes | Yes | **no destination (SW-UI-03)** | structured path via engine when CONDITIONAL | ledger durable |
| FEAT-AI-CONTENT-REPORT | `:features:ai-content-report` | Yes | Yes | ContentReportScreen | draft/queue software | store durable |

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
| Packaging pass | README, version `0.2.0`/`2`, ProGuard JNI/AIDL keeps, CI harden, RELEASE_CHECKLIST human Play steps, detekt skip documented |
| assembleRelease | `:android:app-ui` + `:android:companion-sandbox` **BUILD SUCCESSFUL** (unsigned) |
| Gates re-run | `checkContractDrift` + `checkDependencyEdges` + `checkNative16kb` **OK** |
| Residual human-only | §4 OUT_OF_SCOPE (OO-01…OO-14) — device, OEM, Play Console |
| Top software residuals (non-packaging) | SW-UI-03 (Tools destination), SW-FEAT-03/06/09, SW-DUR-08/09, device qualification (Q1) — see SHIP_BACKLOG.md |

---

*End of PRODUCT_READINESS_CHECKLIST.md — software gate inventory only; no fake evidence.*
