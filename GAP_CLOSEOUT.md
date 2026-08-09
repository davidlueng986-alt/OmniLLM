# OmniLLM Android — Gaps 1–3 Integration Closeout

**Date:** 2026-08-06  
**Repo:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android`  
**Scope:** Integration verification for production gaps **1–3** only.  
**Rules preserved:** ADR-010 single writer; Plan → Reserve → Commit → Execute (Plan pure); INV-001 UI never loads native / writes DB; unknown capability fail-closed; **no** fake QUALIFIED/SUPPORTED; **no** Play Console upload automation.

Authority for status narrative: this file + `BUILD_STATUS.md` § Partial/deferred.  
Earlier inventory (pre-closeout snapshot): `GAP_INVENTORY.md` (line numbers may be stale).

---

## A) Unit tests

| Check | Result |
|---|---|
| Command | `.\gradlew.bat test --continue` |
| Result | **BUILD SUCCESSFUL** (~30s; 302 actionable tasks) |
| Log capture | session terminal / local `test-verify-gaps.txt` when written |

No compile/test breakages required fixing in this verification pass (tree already green).

---

## B) Gap #2 — Production claim/commit not InMemory

**Criterion:** Production `RuntimeControlPlane` must **not** construct claim/commit via `InMemory*` / `createInMemoryWithCommits`.

| Location | Production wiring |
|---|---|
| `android/runtime-service/src/main/.../controlplane/RuntimeControlPlane.kt` | `AndroidSqliteDriver` + `ControlPlaneDatabase.open` → `RequestRegistryModule.createWithCommits(claims=controlDb.claims, commits=controlDb.commits)` |
| Stores | `SqlDelightClaimLedgerStore` / `SqlDelightCommitLedgerStore` / `SqlDelightSessionStore` via `ControlPlaneDatabase` |
| Recovery | `controlPlaneDb.reconcileUnfinishedCommits()` → READY when durable complete; DEGRADED only on reconcile failure |

**Grep (production main):**

- `android/runtime-service/src/main` — **zero** hits for  
  `InMemoryClaimLedgerStore` | `InMemoryCommitLedgerStore` | `createInMemoryWithCommits`
- Remaining `InMemory*` under `src/main` are **not** claim/commit ledgers:
  - `ModelManagerModule.createInMemoryControlPlane()` (model catalog until durable model-manager bind)
  - `WaveAWiring` modelhub display/link ports
  - `FeaturePackHost` `InMemoryToolProposalLedger`
  - `LoopbackTokenService` default `InMemorySecretBroker`

**Test-only** still uses `createInMemoryWithCommits` (allowed):

- `.../featurehost/RuntimeControlPlaneWaveASmokeTest.kt`
- `.../featurehost/FeaturePackHostBootstrapTest.kt`
- `.../binder/AssetHandleBrokerTest.kt` (`createInMemory`)

**Gap #2 status: done**

---

## C) Gap #1 — Feature Pack attach (12 services)

**Criterion:** All 12 Feature Pack domain services constructed on the control plane (or residual documented with path).

### Constructed in production attach

| # | Pack | Service / API | Construction path |
|---|---|---|---|
| 1 | admin | `AdminFeatureApi` | `WaveAWiring.wire` → `AdminFeatureModule.createApi` |
| 2 | auto-setup | `AutoSetupApi` | `WaveAWiring` → `AutoSetupModule.createApi` |
| 3 | modelhub | `ModelHubApi` | `WaveAWiring` → `ModelhubModule.createApi` |
| 4 | playground | `PlaygroundApi` | `WaveAWiring` → `PlaygroundModule.createApi` |
| 5 | server | `DeveloperServerApi` | `WaveAWiring` → `ServerFeatureModule.createApi` |
| 6 | dashboard | `DashboardApi` | `WaveAWiring` → `DashboardFeatureModule.createApi` |
| 7 | lan | `LanAccessApi` | `FeaturePackHost.bootstrap` → `LanFeatureModule.createApi` |
| 8 | benchmark | `BenchmarkApi` | `FeaturePackHost.bootstrap` → `BenchmarkFeatureModule.createApi` |
| 9 | diagnostics | `DiagnosticsApi` | `FeaturePackHost.bootstrap` → `DiagnosticsModule.createApi` |
| 10 | routing | `RoutingApi` | `FeaturePackHost.bootstrap` → `RoutingFeatureModule.createApi` |
| 11 | tools | `ToolsApi` | `FeaturePackHost.bootstrap` → `ToolsFeatureModule.createApi` |
| 12 | ai-content-report | `ContentReportApi` | `FeaturePackHost.bootstrap` → `ContentReportModule.createApi` |

**Host entry:** `RuntimeControlPlane.attach` → `WaveAWiring.wire(...)` then  
`FeaturePackHost.bootstrap(..., waveA = waveA)`  
Plane accessors: `adminFeatureApi` … `toolsApi` / `contentReportApi` (see `RuntimeControlPlane.kt`).

**HTTP projection:** `GatewayLifecycle` passes `lanPorts`, `diagnosticsApi`, `contentReportApi`, `routingApi`, `toolsApi`, `benchmarkApi` + plane `orchestrator` into `ControlPlaneHttpHandler`.

**Host tests:**

- `FeaturePackHostBootstrapTest` — wave-B IDs + markers  
- `RuntimeControlPlaneWaveASmokeTest` — wave-A non-null services  

### Residuals (do not reopen gap #1 “constructed”, but track)

| Residual | Path |
|---|---|
| Inference / smoke still fail-closed until engines SUPPORTED | `WaveAWiring` playground/server ports; `FailClosedInferenceEngine.kt`; `FailClosedFeaturePorts.kt` |
| Tool proposal ledger process-memory | `FeaturePackHost.kt` → `InMemoryToolProposalLedger` |
| Modelhub display/link ports process-memory | `WaveAWiring.kt` → `InMemoryModelDisplayMetadataPort`, `InMemoryAcquisitionLinkStore` |
| Content report default store process-memory | `ContentReportModule` default store (unless overridden) |
| Jobs ledger process-memory | `JobManagerModule.createManager()` → `InMemoryJobStore` |
| UI depth / AIDL surface parity incomplete | `android/app-ui` screens; binder facades vary by pack |

**Gap #1 status: done** (all 12 services constructed on plane)

---

## D) Gap #3 — Native packaging path

**Criterion:** `CMakeLists` or packaged `.so` path exists; if NDK missing, state residual honestly.

| Artifact | Path / status |
|---|---|
| CMake | `android/native/src/main/cpp/CMakeLists.txt` — builds `omnillm_llama` SHARED |
| Sources | `jni_bridge.cpp`, `omnillm_llama.cpp`, `omnillm_llama.h` |
| Gradle | `android/native/build.gradle.kts` — `externalNativeBuild` + `verifyNativeLibsPresent` |
| Packaged consumers | `:android:runtime-service`, `:android:workers` depend on native packaging |
| Built `.so` (this host) | `android/native/build/intermediates/.../arm64-v8a/libomnillm_llama.so` and `.../x86_64/...` |
| NDK | **Present:** `28.2.13676358` under SDK (`local.properties` → `sdk.dir`) |

### Residuals (honest)

| Residual | Notes |
|---|---|
| Implementation is a **JNI shim**, not full upstream llama.cpp | See `engines/llama-cpp/NATIVE.md`, `UPSTREAM.lock` **NOT_LOCKED** |
| Peer engines still stubs / fail-closed | litert-lm, mlc-llm, mllm, ort-genai |
| No QUALIFIED / SUPPORTED elevation | `EngineSelectionPolicy` + `specs/engine-qualification-status.yaml` |
| Real inference on device unproven | Needs human + device + model assets (gap #4) |

**Gap #3 status: done** for packaging path; **upstream full native inference remains open** under gaps #4–#5 / known-gap list.

---

## E) BUILD_STATUS update

`BUILD_STATUS.md` § **Partial / deferred** now marks:

| # | Item | Status |
|---|---|---|
| 1 | Feature Pack control-plane attach | **done** |
| 2 | Durable SQLite claim/commit | **done** |
| 3 | Native CMake / `.so` path | **done** (shim) |
| 4–5 | Device E2E / Play upload | **blocked** (human/device; Play out of scope) |

Snapshot rows for native + durable DB + Feature Packs refreshed to match.

---

## F) Gaps #4–#5 — still need human / device (not closed here)

| # | Topic | Why not closed in-repo | What human/device must do |
|---|---|---|---|
| **4** | Device multi-process E2E + engine qualification | Host tests cannot mint PASS evidence; all cells `UNQUALIFIED` / `NOT_EXECUTED` | Install APKs on real devices; run qualification harness; record time-bounded PASS only with real envelopes; **never** invent PASS |
| **5** | Play Console upload / release ops | Explicitly **out of scope** for this automation; `release.yml` builds only | Human Play Console (or approved CI secrets) for signing + upload; multi-APK companion validation |

Related residuals that may ride with #4:

- Full llama.cpp / SDK pin + worker kill semantics under load  
- Crash recovery fixtures on-device after durable claim/commit  
- Instrumentation beyond single smoke test  

---

## Verification matrix (gaps 1–3)

| Check | Pass? |
|---|---|
| A `./gradlew test` green | **Yes** |
| B Production claim/commit InMemory absent | **Yes** (gap #2 done) |
| C 12 Feature Pack services on plane | **Yes** (gap #1 done) |
| D CMakeLists + `.so` + NDK present | **Yes** (gap #3 packaging done) |
| Fake Play upload | **Not added** |
| Fake qualification PASS | **Not added** |

---

## Files changed by this closeout task

| File | Action |
|---|---|
| `BUILD_STATUS.md` | Updated snapshot, Partial/deferred (1–3), known gaps, milestones, ADR-004/005 note |
| `GAP_CLOSEOUT.md` | **Created** (this document) |

No production Kotlin/C++ changes required for this verification pass (implementation already landed; this task was integration verification + status docs).

---

## Remaining blockers (path-indexed)

| ID | Blocker | Paths |
|---|---|---|
| R4 | Device qualification / multi-process E2E | `specs/engine-qualification-status.yaml`; thin `android/app-ui` androidTest |
| R5 | Play upload automation | `.github/workflows/release.yml`; out of scope |
| R-N | Full upstream native + lock digests | `engines/llama-cpp/UPSTREAM.lock`, `NATIVE.md`; shim under `android/native/src/main/cpp/` |
| R-J | Jobs not durable | `runtime/job-manager/.../JobStore.kt` `InMemoryJobStore` |
| R-CR | Content report store process-memory | `features/ai-content-report` default store |
| R-SEC | Secrets/tokens default in-memory broker | `LoopbackTokenService.kt`, `PolicyModule` security stack defaults |
| R-SCHEMA | SQLDelight subset vs authority SQL | `data/persistence` `.sq` vs `resources/db/omnillm-schema.sql`; `applySchema=false` open path |

---

## G) Stage 4 addendum (2026-08-09) — residual blocker closeout

Follow-up wave (launch-readiness fix; branch `fix/launch-readiness`) resolved the
post-closeout residuals listed above:

| ID | Historical residual | Current status | Evidence / commit |
|---|---|---|---|
| R-N | Full upstream native + lock digests | **CLOSED** | llama.cpp **LOCKED** b9999/47c7869 with source/toolchain/artifact digests (`105856a`, `UPSTREAM.lock`); real GGUF verified on emulator (`RealLlamaUpstreamInstrumentedTest`); peers: litert/ort/mllm LOCKED, mlc NOT_LOCKED w/ pin (status yaml synced, FTR-04 `17a9745`) |
| R-J | Jobs not durable | **CLOSED** | `Jobs.sq`/`JobAttempts.sq`/`JobEvents.sq` + `SqlDelightJobLedgerStore`; `JobManagerModule.createDurableManager(ports=controlDb.jobs)` + `reconcileAfterRestart()` |
| R-CR | Content report store process-memory | **CLOSED** | `ContentReports.sq` + `SqlDelightContentReportStore` via `ContentReportModule.createDurableApi` |
| R-SEC | Secrets/tokens default in-memory broker | **CLOSED** | `ControlPlaneSecurityFactory.createSecurityStack` (Keystore-wrapped vault + `AccessTokens.sq`/`PairingChallenges.sq`/`SecretBrokerKeys.sq`/`RevocationSubjects.sq` + `SqlDelightSecretLedgerStore`) |
| R-SCHEMA | SQLDelight subset vs authority SQL | **PARTIAL — improved** | **26/51** tables implemented and column-identical to authority SQL (API-40..44 `1c66aff`); 25 remaining marked `-- STATUS: PLANNED (not yet implemented)`; `applySchema=false` bootstrap path still open (SW-DUR-08) |

**Remaining after Stage 4:** R4 (device qualification — llama emulator smoke is the
first real data point, all engines still UNQUALIFIED), R5 (Play ops), plus the
software residuals in `PRODUCT_READINESS_CHECKLIST.md` (SW-UI-03 Tools destination,
SW-FEAT-03/06/09, SW-DUR-09) and `SHIP_BACKLOG.md` (Q1–Q4, P1–P8).
