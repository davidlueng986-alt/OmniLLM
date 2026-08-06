# OmniLLM Android — Build / Implementation Status

**As-of inventory date:** 2026-08-06 (release packaging pass)  
**Repo root:** `omnillm-android/`  
**Authority:** tree on disk + `specs/` + product `GOV-RISKS` (`governance/risk-register.md`).  
**Scope:** engineering status only. Not a product roadmap or marketing summary.  
**Closeout:** see root `GAP_CLOSEOUT.md` for gaps 1–3 verification evidence; software gates in `PRODUCT_READINESS_CHECKLIST.md`.

> Root `README.md` is the monorepo entry point and points here for status. **This file is the current status inventory.**

---

## 1. Snapshot

| Area | Status |
|---|---|
| Module graph | **43** Gradle modules included in `settings.gradle.kts` |
| Machine-readable contracts | `specs/` present (copy of product package authority) |
| Contract codegen | `tools/codegen/generate_contracts.py` → `core/{canonical,state,errors}/…/generated/` |
| Control-plane / process topology | **Implemented** (UI / `:runtime` / `:engine_worker` / `:parser` / companion package) |
| Feature Packs | **12/12 modules** + **12/12 hosted on control plane** (`FeaturePackHost` wave-A+B in `RuntimeControlPlane.attach`) |
| Engine Packs | **5 adapters + `engines:api`**; all **design BASELINE / runtime UNQUALIFIED**; attach-after-READY path present; cells not SUPPORTED without evidence |
| Native engine `.so` / NDK sources | **Present:** `android/native/src/main/cpp/CMakeLists.txt` + JNI shim → `libomnillm_llama.so` (arm64-v8a / x86_64); NDK 28.2 available; **full upstream llama.cpp still NOT_LOCKED** |
| Durable DB writer | **Claim/commit/session SQLite-backed** via `ControlPlaneDatabase` + SQLDelight stores in production `RuntimeControlPlane.attach` (jobs/content-report/secrets still in-memory) |
| Unit / host tests | `./gradlew test` **BUILD SUCCESSFUL** (2026-08-06 integration verification); host unit tests + 1 `androidTest` smoke |
| Local APK artifacts observed | `app-ui` debug + unsigned release; `companion-sandbox` debug + release under `build/outputs/apk/` |
| App version line | **0.1.0** / `versionCode` **1** (main + companion via `libs.versions.toml`) |
| CI workflows | `.github/workflows/ci.yml`, `release.yml` — test + assemble + contract drift + 16 KB + dep edges; local parity `tools/ci/local_ci.{sh,ps1}` |
| ProGuard / R8 | Keep rules for AIDL + JNI wired; release `isMinifyEnabled=false` until smoke (see `RELEASE_CHECKLIST` §H) |
| detekt | **Intentionally skipped** (not configured; documented in `tools/ci/README.md`) |
| Engine qualification cells | All engines `UNQUALIFIED` / `NOT_LOCKED` / projected `UNKNOWN` (`specs/engine-qualification-status.yaml`) |

---

## 2. Module tree

Source of truth: `settings.gradle.kts`.

```text
omnillm-android/
├── specs/                          # Canonical catalogs (types, errors, states, OpenAPI, AIDL, SQL, …)
├── tools/
│   ├── codegen/                    # generate_contracts.py, extract_aidl.py
│   └── ci/                         # local_ci, dep edges, 16 KB ELF/APK checks
├── docs/architecture/              # Implementation notes (not product authority)
├── gradle/libs.versions.toml       # Toolchain lock
│
├── core/                           # Pure Kotlin (no Android SDK)
│   ├── canonical                   # Types, digests, catalogs (incl. generated/)
│   ├── state                       # FSM drivers + generated machines
│   ├── contracts                   # Plan / Reserve / Commit / Execute shapes
│   ├── errors                      # Error catalog mapping (incl. generated/)
│   ├── resource                    # ResourceVector arithmetic / conservation
│   └── identity                    # Blob / ArtifactPackage / ModelRevision / Installation
│
├── data/                           # Control-plane write surfaces only (ADR-010)
│   ├── persistence                 # Schema authority, SQLDelight, claim/commit DAO ports
│   └── model-store                 # Content-addressed quarantine / ready content ports
│
├── runtime/                        # Single-writer control plane libraries
│   ├── request-registry            # Claim-or-return, commit ledger, query (ADR-004/005)
│   ├── orchestrator                # Plan / route / schedule / fallback
│   ├── governor                    # Multi-dim reservation / pressure ledger
│   ├── session                     # Session pool / poison / drain
│   ├── model-manager               # Install / trust / load coordination
│   ├── job-manager                 # Recoverable jobs
│   ├── policy                      # Settings, ACL, tokens, secrets, download URL policy
│   └── observability               # Metrics, traces, redaction, health, evidence labels
│
├── engines/
│   ├── api                         # OmniEngine SPI, registry, FakeEngine
│   ├── llama-cpp                   # Adapter + StubNativeBackend
│   ├── litert-lm                   # Adapter + StubSdkBackend
│   ├── mlc-llm                     # Adapter + exploratory runtime stub
│   ├── mllm                        # Adapter + private server channel stub
│   └── ort-genai                   # Adapter stub (no ORT/GenAI natives)
│
├── interfaces/
│   ├── http                        # OpenAPI projection, Ktor loopback gateway
│   ├── aidl                        # 44 .aidl + parcelable markers
│   └── admin                       # AdminApiService for LOCAL_UI
│
├── features/                       # Feature Packs (capability-scoped domain)
│   ├── auto-setup
│   ├── modelhub
│   ├── playground
│   ├── server
│   ├── lan
│   ├── dashboard
│   ├── benchmark
│   ├── diagnostics
│   ├── routing
│   ├── tools
│   ├── admin
│   └── ai-content-report
│
└── android/                        # Process / OS adapters
    ├── app-ui                      # Main package com.omnillm — UI process only (INV-001)
    ├── runtime-service             # Merged into process :runtime — FGS, binder, HTTP gateway
    ├── workers                     # process :engine_worker
    ├── parser-isolated             # process :parser (isolatedProcess)
    ├── companion-sandbox           # Separate APK com.omnillm.companion (ADR-007)
    └── native                      # CMake/JNI shim libomnillm_llama.so (16 KB page-size gates)
```

**Packages / processes**

| Artifact | applicationId | Process role |
|---|---|---|
| `:android:app-ui` | `com.omnillm` (+ `.debug` suffix) | Default UI process |
| `:android:runtime-service` | merged into main app | `:runtime` (FGS, Admin/Runtime binder, loopback HTTP) |
| `:android:workers` | merged | `:engine_worker` (same-UID crash isolation) |
| `:android:parser-isolated` | merged | `:parser` (`isolatedProcess`) |
| `:android:companion-sandbox` | `com.omnillm.companion` | Different UID companion |

---

## 3. Features — what exists

All twelve Feature Pack modules are included and have main sources + unit tests. Typical layout: `domain/` + `policy/` + `usecase/*Service` + `projection/` + feature module object.

| Module | Primary service / surface | Unit tests | UI surface in `:android:app-ui` |
|---|---|---|---|
| `:features:auto-setup` | `AutoSetupService`, recommendation ranker | Yes | `OnboardingScreen` |
| `:features:modelhub` | `ModelHubService` | Yes | `ModelHubScreen` |
| `:features:playground` | `PlaygroundService` | Yes | `PlaygroundScreen` |
| `:features:server` | `DeveloperServerService` | Yes | `ServerClientsScreen` |
| `:features:lan` | `LanAccessService` + pairing/auth policy | Yes | (policy via server/settings paths) |
| `:features:dashboard` | `DashboardService` | Yes | `DashboardScreen` |
| `:features:benchmark` | `BenchmarkService` | Yes | (service-level; no dedicated screen name) |
| `:features:diagnostics` | `DiagnosticsService`, bundle builder | Yes | `DiagnosticsScreen` |
| `:features:routing` | `RoutingService`, alias/fallback/session continuity | Yes | (service-level) |
| `:features:tools` | `ToolsService`, structured-mode policy, schema limits | Yes | (service-level) |
| `:features:admin` | Admin/job projections | Yes | `SettingsScreen`, Home |
| `:features:ai-content-report` | `ContentReportService` | Yes | `ContentReportScreen` |

**Wiring note (verified 2026-08-06):** `:android:runtime-service` `RuntimeControlPlane.attach` hosts **all 12** Feature Pack domain APIs via `WaveAWiring` + `FeaturePackHost.bootstrap(waveA=…)`:

| Wave | Packs (services) |
|---|---|
| A | admin, auto-setup, modelhub, playground, server, dashboard |
| B | lan, benchmark, diagnostics, routing, tools, ai-content-report |

HTTP handler projects wave-B surfaces; engine execute paths remain **fail-closed** until registry cells are SUPPORTED with real evidence. Some secondary ports (e.g. tool proposal ledger, modelhub display metadata) remain process-memory.

---

## 4. Engines — what exists

| Module | engineId | Design | Upstream lock | Qualification | Runtime backend |
|---|---|---|---|---|---|
| `:engines:api` | (SPI) | — | — | — | `FakeEngine` for Plan→Execute without natives |
| `:engines:llama-cpp` | `llama.cpp` | BASELINE | `NOT_LOCKED` | `UNQUALIFIED` | JNI shim `libomnillm_llama` packaged; host tests may use `StubNativeBackend`; **not** QUALIFIED |
| `:engines:litert-lm` | `LiteRT-LM` | BASELINE | `NOT_LOCKED` | `UNQUALIFIED` | `StubSdkBackend` (no real SDK/AAR) |
| `:engines:mlc-llm` | `MLC-LLM` | BASELINE | `NOT_LOCKED` | `UNQUALIFIED` | Exploratory runtime stub |
| `:engines:mllm` | `mllm` | BASELINE | `NOT_LOCKED` | `UNQUALIFIED` | Private server channel stub (no AAR) |
| `:engines:ort-genai` | `ONNX-Runtime-GenAI` | BASELINE | `NOT_LOCKED` | `UNQUALIFIED` | Execute paths fail closed / `CAPABILITY_UNKNOWN` |

Each pack ships:

- `UPSTREAM.lock` template (empty digests ⇒ `NOT_LOCKED`)
- `capability-matrix.yaml` with UNQUALIFIED placeholder cells
- Mapping helpers (errors / phase cancel / resource envelope estimators)
- Unit tests for lock parse, mapping, and scaffold pipeline

**Hard rule enforced in code + specs:** design `BASELINE` does **not** project registry `SUPPORTED`. Only non-expired PASS evidence with qualified envelope may do so (`specs/engine-qualification-status.yaml`).

`RuntimeControlPlane.ensureEnginePacksAttached()` registers catalog engines after READY/DEGRADED (`EnginePackAttachment.attachAfterReady`); llama-cpp loads packaged `libomnillm_llama` when present. **No cell is elevated to SUPPORTED/QUALIFIED without device evidence.**

---

## 5. Core platform depth (factual)

### Implemented (library + tests)

- Canonical types / enums / digests / resource vectors (codegen + hand logic)
- State machine driver + illegal-edge / commit-reconcile fixture tests
- Plan / Commit contract shapes + reconcile tests
- Error catalog mapping
- Identity layers (Blob / ArtifactPackage / ModelRevision)
- Resource conservation properties + governor ledger
- Request registry claim-or-return + commit ledger recovery tests
- Orchestrator plan/route/schedule/fallback + unknown-capability fail-closed
- Session manager (production durable via SQLDelight on plane; in-memory fixtures in unit tests) + poisoned-session reuse negatives
- Model manager install/load/supply-chain hooks (catalog root bootstrap tests)
- Job manager
- Policy: settings merge, ACL, token/secret broker, pairing challenges, download URL/DNS policy, JSON tree budgets
- Observability: metrics registry, redaction, traces, health, evidence labels
- HTTP: OpenAPI path inventory, loopback gateway (Ktor), SSE framing, claim DTOs, security negative tests
- AIDL: 44 `.aidl` under `interfaces/aidl`, binder facades in runtime-service
- Admin facade + LOCAL_UI principal models
- Model-store quarantine / layout / stream materializer (filesystem-oriented)
- Companion command gate, ticket validation, host placement gate
- Worker command gate + journal
- Isolated parser bounds engine
- Compose UI shell with destinations for onboarding, hub, playground, server, dashboard, diagnostics, settings, content report

### Partial / deferred

| # | Item | Status | Evidence in tree |
|---|---|---|---|
| **1** | Full Feature Pack → control-plane attach | **done** | All 12 services constructed in `RuntimeControlPlane.attach` via `WaveAWiring` + `FeaturePackHost.bootstrap`; accessors on plane; host unit smoke tests |
| **2** | Durable SQLite for claim/commit ledgers | **done** | Production path: `AndroidSqliteDriver` + `ControlPlaneDatabase.open` → `SqlDelightClaimLedgerStore` / `SqlDelightCommitLedgerStore` / sessions; `RequestRegistryModule.createWithCommits` — **no** `InMemoryClaim*` / `InMemoryCommit*` / `createInMemoryWithCommits` in `runtime-service` **main** |
| **3** | Native packaging path (CMake / `.so`) | **done** (shim) | `android/native/src/main/cpp/CMakeLists.txt`, `jni_bridge.cpp`, `omnillm_llama.{h,cpp}`; built `libomnillm_llama.so` for arm64-v8a + x86_64; NDK **28.2.13676358** present on this host |
| — | Real upstream engine inference / QUALIFIED | **partial / blocked on device** | Shim + fail-closed execute until SUPPORTED; all engines `UNQUALIFIED` / `NOT_LOCKED`; no fake PASS |
| 4 | Full multi-process E2E on device | **blocked** (human/device) | Host unit tests pass; one instrumented smoke; no device qualification suite |
| 5 | Signed Play upload automation | **blocked / out of scope** | `release.yml` signing secrets optional; Play Console upload **not wired** (explicitly not automated here) |
| — | detekt / static analysis suite | **skipped (documented)** | Not configured; AGP lint + unit tests + architecture gates required (`tools/ci/README.md`) |
| — | Jobs / content-report durability (secondary) | **partial** | Claim/commit/session durable; secrets/tokens durable on plane; jobs/content-report secondary stores still process-memory defaults |

---

## 6. Toolchain lock

From `gradle/libs.versions.toml` / `README.md`:

| Tool | Locked value |
|---|---|
| JDK | 17 |
| Gradle | 9.5.0 |
| AGP | 9.3.0 |
| Kotlin | 2.2.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 28 (product decision) |
| NDK | 28.2.13676358 |
| Build-tools | 36.0.0 |

---

## 7. How to build / test / run CI

### Prerequisites

- JDK 17+
- Android SDK platform 36, build-tools 36.0.0
- NDK 28.2.x when assembling packages that package JNI (gates pass with zero `.so`)
- Python 3 + `pip install -r tools/codegen/requirements.txt` for contract gates

### Common commands

```bash
# Module list
./gradlew omnillmModules

# Contract generate + drift (fail closed on catalog vs committed generated)
./gradlew generateContracts
./gradlew checkContractDrift

# Dependency / architecture edges
./gradlew checkModuleDependencyRules   # or: python tools/ci/check_module_dependency_rules.py
./gradlew checkDependencyEdges         # INV-001 / engines↛data

# Unit + host Android unit tests (fail closed)
./gradlew test
./gradlew jvmTest
./gradlew androidUnitTest

# Root verification bundle
./gradlew check                        # drift + 16 KB ELF scan + dep edges
./gradlew checkNative16kb

# Assemble
./gradlew :android:app-ui:assembleDebug
./gradlew :android:app-ui:assembleRelease
./gradlew :android:app-ui:bundleRelease
./gradlew :android:companion-sandbox:assembleDebug
./gradlew :android:companion-sandbox:assembleRelease

# Focused examples
./gradlew :core:state:test
./gradlew :runtime:orchestrator:test
./gradlew :interfaces:http:test
./gradlew :android:runtime-service:testDebugUnitTest
```

Windows: `.\gradlew.bat` / `.\tools\ci\local_ci.ps1`.

### Local CI parity

```bash
bash tools/ci/local_ci.sh
bash tools/ci/local_ci.sh --skip-assemble
```

```powershell
.\tools\ci\local_ci.ps1
.\tools\ci\local_ci.ps1 -SkipAssemble
```

Pipeline order (authoritative in `tools/ci/README.md` / `.github/workflows/ci.yml`):

1. Specs authority present  
2. `checkContractDrift` (**before** regenerate)  
3. Optional generate + clean tree check  
4. Module dependency rules (**fail closed**)  
5. `./gradlew test` (JVM + Android host unit tests)  
6. Root `check` (drift + 16 KB + edges)  
7. Lint (`app-ui`, `companion-sandbox`)  
8. `assembleDebug` / unsigned `assembleRelease`  
9. ELF 16 KB + APK zip-align checks  
10. Upload APK artifacts  

Release signing: GitHub secrets only (`SIGNING_KEYSTORE_BASE64`, store/key passwords, alias). Missing secrets → unsigned release with warning. Never commit keystores.

### Product package validation (separate tree)

```bash
python <product_package>/tools/validate_repository.py <product_package>
```

This monorepo must not modify the product package; re-copy `specs/` when product contracts revise.

---

## 8. Known gaps (implementation)

Ordered by impact on “can run real local inference on device.”  
**Gaps 1–3 closed at integration verification (2026-08-06)** — see `GAP_CLOSEOUT.md`.  
**Software packaging closed (2026-08-06 release packaging pass)** — README, version line, ProGuard keeps, CI harden, release checklist.

### Closed (software)

1. ~~Feature packs not host-wired~~ → **closed**: 12/12 APIs on control plane.  
2. ~~Claim/commit ledgers in-memory at runtime~~ → **closed**: SQLDelight / `ControlPlaneDatabase` in production attach.  
3. ~~No native CMake / packaged `.so` path~~ → **closed** (JNI shim); full upstream still open (human/device residual).  
12. ~~Root README status blurb stale~~ → **closed**: README rewritten; points at this file + `PRODUCT_READINESS_CHECKLIST.md` + `AGENTS.md`.  
13. ~~Release version line skeleton~~ → **closed**: `appVersionName=0.1.0` / `versionCode=1` (main + companion catalog).  
14. ~~ProGuard JNI/AIDL keep rules incomplete~~ → **closed**: app-ui + consumer-rules for AIDL stubs + `JniNativeBridge`.  
15. ~~CI missing explicit dep-edge / release assemble emphasis~~ → **closed**: `ci.yml` / `release.yml` / `local_ci` run test + assemble + contract drift + 16 KB + dep edges.  
16. ~~RELEASE_CHECKLIST mixed software/Console~~ → **closed**: human Play steps primary; software pre-gates in §0 only.  
17. ~~detekt unclear~~ → **closed as intentional skip** (documented; not a software blocker).

### Open — software residual (not packaging)

6. **SQLDelight is a subset** — full DDL/triggers remain authority SQL; bootstrap `applySchema=false` path vs full product DB design residual.  
7. **Secondary ledgers still in-memory** — jobs, content-report store (secrets/tokens durable on production plane).  
9. **UI depth** — Compose screens/shell exist; live Admin binder projection depth varies; some feature destinations may still be partial vs product IA (see `PRODUCT_READINESS_CHECKLIST.md`).  
18. **Exploratory CONDITIONAL execute / inference ports** — may still fail-closed depending on plane wiring; matrix must stay UNKNOWN/UNQUALIFIED (see product readiness SW-ENG / SW-FEAT).  

### Open — human / device / Play only (not software blockers for monorepo packaging)

4. **No real Engine Pack upstream pins / full GGUF inference** — all `UPSTREAM.lock` templates `NOT_LOCKED`; do **not** mark QUALIFIED/SUPPORTED without device evidence.  
5. **No device qualification evidence** — all engine cells `UNQUALIFIED` / evidence `NOT_EXECUTED` (needs human + physical devices).  
8. **Instrumentation coverage thin on device** — one smoke under `app-ui/androidTest`; process isolation/Binder security mostly host unit tests.  
10. **Play automated upload not wired** — release workflow builds artifacts only; Console upload is **human-only** (`gradle/RELEASE_CHECKLIST.md`).  
11. **Play Console questionnaires / signing ceremony** — FGS, Data Safety, AI reporting, privacy URL, App Signing secrets (human).

---

## 9. Residual risks from `GOV-RISKS` that remain

Source: product `governance/risk-register.md` (`GOV-RISKS`, BASELINE / NORMATIVE).  
These are **accepted residual risks** in design; implementation does not eliminate them. Status below is engineering mitigation progress only.

| ID | Residual risk (normative) | Design handling | In-repo mitigation status |
|---|---|---|---|
| **R-001** | Malicious native may exceed memory envelope / stress system | Sandbox, conservative floor, quota, pressure fail-safe, kill; no hard RSS claim | Governor + resource conservation code exist; JNI **shim** may load; envelope under **full upstream** engines unproven. Residual risk **remains**. |
| **R-002** | OEM driver/kernel defects can impact system across processes | Qualification, worker isolation, fallback, device deny/quirk, explicit residual risk | Process topology (`:engine_worker`, companion UID, isolated parser) **scaffolded**; **no device/driver qualification cells PASS**. Residual risk **remains**. |
| **R-003** | Model output may be harmful or wrong | Trust ≠ content quality; in-app report/flag, warnings, diagnostics; no correctness guarantee | `:features:ai-content-report` + UI screen + control-plane ContentReport API **present**. Residual content-risk **remains by design**. |
| **R-004** | iOS (etc.) lack Android-style isolated UID | Platform placement matrix; refuse combinations without boundary | **Android-only monorepo**; future platforms not implemented. Residual risk **remains** for non-Android. |
| **R-005** | Catalog root compromise | Threshold signatures, root rotation, revocation, fresh verification; incident recovery still required | Model-manager supply-chain hooks / catalog root bootstrap **code + tests**; production root ceremony / multi-sig **not operationalized** in-tree. Residual risk **remains**. |
| **R-006** | User-shared diagnostics leave control | Export preview, redaction, encryption options, clear warnings | Observability redaction + diagnostics feature **present**; full export-preview/encryption UX depth still incomplete vs product docs. Residual risk **remains**. |
| **R-007** | Background execution limited by OS policy | Job pause / wait-for-foreground; no fixed restore-time promises | Runtime lifecycle controller + FGS services + job manager **present**; OS kill/FGS policy behavior **not field-validated** across OEMs. Residual risk **remains**. |

**Additional engineering risks (not numbered in GOV-RISKS but material):**

- Claim/commit are SQLite-durable; residual crash paths for **jobs / content-report / secrets** still process-memory.  
- Shim/`StubNativeBackend` peers may mask integration bugs that only appear with full upstream JNI cancel semantics.  
- Companion package must be installed and same-signer for untrusted acceleration; distribution multi-APK strategy is documented (`PACKAGING.md`) but operational Play multi-package validation is open.

---

## 10. Architecture invariants — enforcement status

| Rule | Enforcement observed |
|---|---|
| INV-001 UI never loads native / writes DB | Module dep gates (`check_module_dependency_rules.py`, `check_dependency_edges.py`); app-ui depends on admin/canonical projections only |
| ADR-002 Plan no domain mutation | Orchestrator/engine plan paths + unit tests; engine stubs keep plan pure |
| ADR-010 single writer | `SingleWriterPolicy` / `ControlPlaneWriter`; runtime-service owns attach |
| ADR-008 identity separation | `:core:identity` types + golden tests |
| ADR-009 multi-dimension eval | Model-manager evaluation dimensions present |
| ADR-007 companion different UID | Separate `applicationId`, packaging docs, placement gate tests |
| ADR-004/005 claim-or-return | Request registry + ledger tests; production SQLDelight claim/commit bound in `:runtime` |
| ADR-011 shared semantics | HTTP OpenAPI + AIDL projections; transport guarantees documented in code |
| INV-018 fail closed | Unknown capability / unproven engine ops unit-tested |

---

## 11. Suggested next engineering milestones (not commitments)

1. ~~Bind claim/commit to SQLDelight in runtime bootstrap~~ (done). Prove crash recovery fixtures **on device**.  
2. ~~Host all Feature Packs on control plane~~ (done). Deepen Admin/HTTP/AIDL transport parity + UI projections.  
3. Complete one engine `UPSTREAM.lock` digests + replace JNI shim with real llama.cpp (or LiteRT-LM) behind killable worker; keep cells `UNQUALIFIED` until evidence.  
4. Device qualification harness → first PASS cells (human + devices; no fake PASS).  
5. Durable jobs / content-report / secrets stores under ADR-010.  
6. ~~Refresh root `README.md` status section to point at this file~~ (done — packaging pass).

---

## 12. Files / surfaces for status refresh

When updating this document, re-check at minimum:

- `settings.gradle.kts` (module set)
- `specs/engine-qualification-status.yaml` + each `engines/*/UPSTREAM.lock`
- `android/runtime-service/.../RuntimeControlPlane.kt` (wiring TODOs)
- `tools/ci/README.md` + `.github/workflows/ci.yml`
- product `governance/risk-register.md` (`GOV-RISKS`)
- test inventory under `**/src/test/**`
- APK outputs under `android/*/build/outputs/` (local only; not committed)

---

*End of BUILD_STATUS.md — factual inventory only.*
