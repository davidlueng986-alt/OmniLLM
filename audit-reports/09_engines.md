# 09 — ENGINE-* Matrix Audit

**Auditor:** senior independent (fail-closed)  
**Date:** 2026-08-12  
**Docs authority:** `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents`  
**Implementation:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android`  
**Artifacts:** this file + `09_engines.json`  
**Rules applied:** path evidence only; statuses ∈ {PASS | PARTIAL | MISSING | N_A | BLOCKED_HUMAN}; no invented device/Play/OEM PASS; QUALIFIED/SUPPORTED only with matching evidence.

---

## 0. Authority sources grepped/read

| Source | Path | Role |
|---|---|---|
| ENGINE-STANDARD | docs package `docs/80-engines/engine-integration-standard.md` | Adapter / lock / qualification rules |
| ENGINE-QUALIFICATION-STATUS | docs package `docs/80-engines/qualification-status-and-evidence.md` | Dual-state; no fake evidence |
| Engine packs | docs package `docs/80-engines/{llama-cpp,litert-lm,mlc-llm,mllm,onnx-runtime-genai}.md` | Per-engine design (all `BASELINE`) |
| Qualification status (docs) | docs package `specs/engine-qualification-status.yaml` | Pre-build: all `NOT_LOCKED` + `UNQUALIFIED` |
| Qualification schema | docs package + monorepo `specs/engine-qualification-schema.yaml` | Cell dimensions, evidence values |
| Qualification status (impl) | monorepo `specs/engine-qualification-status.yaml` | Lock/integration notes (implementation SSOT) |
| UPSTREAM.lock ×5 | `engines/{llama-cpp,litert-lm,mlc-llm,mllm,ort-genai}/UPSTREAM.lock` | Supply-chain pin |
| capability-matrix.yaml ×5 | same engine dirs | Cell seed / runtimeDefault UNKNOWN |
| EngineRegistry | `engines/api/.../EngineRegistry.kt` | Projection: only QUALIFIED_WITH_ENVELOPE + PASS → SUPPORTED |
| Attach / policy / execute | `android/runtime-service/.../EnginePackAttachment.kt`, `EngineSelectionPolicy.kt`, `EngineExecuteBinding.kt` | L2 wiring |
| Native | `android/native/src/main/cpp/**`, built `libomnillm_llama.so` | llama packaging |
| Catalog | `settings.gradle.kts` includes only the five packs below (+ `:engines:api`) |

**No additional engine packs** found beyond: `llama-cpp`, `litert-lm`, `mlc-llm`, `mllm`, `ort-genai`.

---

## 1. Cross-cutting findings

### 1.1 Honest UNQUALIFIED / no false SUPPORTED — **PASS**

| Check | Evidence | Status |
|---|---|---|
| Docs package cells | All five engines: `qualificationStatus: UNQUALIFIED`, `runtimeCapabilityDefault: UNKNOWN` | PASS |
| Monorepo `specs/engine-qualification-status.yaml` | Same UNQUALIFIED/UNKNOWN for all; lock notes explicitly “NOT a SUPPORTED claim” | PASS |
| All capability-matrix cells | Every seeded cell `qualificationStatus: UNQUALIFIED` + `evidenceStatus: NOT_EXECUTED` | PASS |
| Registry projection | `EngineRegistry.projectRuntimeCapability`: only `QUALIFIED_WITH_ENVELOPE` + evidence `PASS` → `SUPPORTED`; missing cell → `UNKNOWN` | PASS |
| Seed path | Modules seed only UNQUALIFIED placeholders (`LlamaCppModule.seedUnqualifiedPlaceholders`, peers analogous) | PASS |
| Dev execute honesty | `EngineExecuteBinding.resolveCapability` projects **CONDITIONAL** with `development_ship_mode` — never plain SUPPORTED without PASS (COR-10) | PASS |
| Attach assert | `EnginePackAttachment.attachAfterReady` checks `!anyExecutableCell` under fail-closed (no invented SUPPORTED cells) | PASS |

**CRITICAL false SUPPORTED/QUALIFIED:** **none found** in engine packs, matrices, UPSTREAM.lock notes, or registry seed code.

### 1.2 Design status (docs) vs implementation lock

| Dimension | Docs package (`specs/engine-qualification-status.yaml`) | Monorepo implementation |
|---|---|---|
| designStatus | BASELINE ×5 | BASELINE ×5 |
| upstreamLockStatus | **NOT_LOCKED ×5** (pre-build design package) | llama/LiteRT/mllm/ORT: **LOCKED**; MLC: **NOT_LOCKED** |
| qualificationStatus | UNQUALIFIED ×5 | UNQUALIFIED ×5 |

Audit treats **monorepo UPSTREAM.lock + monorepo specs** as implementation truth, and docs package as design baseline. Design-vs-lock independence is intentional (ENGINE-QUALIFICATION-STATUS §1).

### 1.3 L1 / L2 / L3 summary

| Level | Meaning | Catalog result |
|---|---|---|
| **L1** module exists | Gradle module + adapter Kotlin + lock/matrix resources | **PASS** for all five + `:engines:api` |
| **L2** wired to control plane | Registry registration after READY; live backend attach | **llama-cpp PASS**; peers **PARTIAL** (metadata/cells only) |
| **L3** product journey software-complete | Orchestrator execute → real model path as production-supported | **PARTIAL** llama (dev CONDITIONAL / exploratory); peers **MISSING**; all remain **UNQUALIFIED** for product SUPPORTED |

**Critical L2 gap:** `EnginePackAttachment.attachAfterReady` creates a live engine **only** for llama-cpp (`createEngineWithNativeOrNull`). Peers call `registerWith` / seed cells only — comment: “metadata + UNQUALIFIED cells only … no real native/SDK load”. Despite `EngineSelectionPolicy.mayUseRealNativeBackend` allowing all catalog engines in development ship mode, **attach does not instantiate** LiteRT/MLC/mllm/ORT engines for the control plane. `EngineExecuteBinding.applyAttachment` binds **only** `LlamaCppInferenceEngineAdapter`.

### 1.4 HIGH (non-CRITICAL) inconsistencies

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| ENG-DRIFT-01 | HIGH | `engines/llama-cpp/capability-matrix.yaml` still has `upstreamLockStatus: NOT_LOCKED` while `UPSTREAM.lock` is `lockState: LOCKED` and monorepo specs say LOCKED | matrix L12 vs lock L28 vs `specs/engine-qualification-status.yaml` L9 |
| ENG-DRIFT-02 | MEDIUM | `engines/llama-cpp/NATIVE.md` still claims digests empty ⇒ NOT_LOCKED (stale vs filled lock) | NATIVE.md L29–30 vs UPSTREAM.lock digests |
| ENG-WIRE-01 | HIGH | Peer engines not live-attached on production attach path; docs `engine-registry-attachment.md` overstates “Backend on attach (dev build)” for peers | `EnginePackAttachment.kt` L85–93, L238–309 vs docs table rows LiteRT/MLC/mllm/ORT |
| ENG-PKG-01 | HIGH | LiteRT Android AAR and ORT GenAI AAR **not** declared as `implementation` on `:android:runtime-service` (Stage 5 noted in pack READMEs/locks) | `runtime-service/build.gradle.kts` L118–127; litert/ort build.gradle.kts `compileOnly` only |
| ENG-MLC-01 | HIGH (by design fail-closed) | MLC remains NOT_LOCKED; no `mlc4j` / generated lib in tree | `engines/mlc-llm/UPSTREAM.lock` `lockState: NOT_LOCKED`; empty digests |

---

## 2. ENGINE-STANDARD conformance (control plane)

| Requirement | Status | Evidence |
|---|---|---|
| Registry only publishes evidenced cells as SUPPORTED | **PASS** | `EngineRegistry.kt` L78–104 |
| Incomplete lock ≠ QUALIFIED | **PASS** | Per-pack UpstreamLock `isComplete()`; notes in locks |
| Adapters must not write DB/model store | **PASS** (documented + attach KDoc) | ENGINE-STANDARD §3; `EnginePackAttachment` KDoc L32 |
| Plan no KV/domain mutation | **PASS** (software SPI) | `OmniEngine` / engine adapters plan purity (unit tests per pack) |
| Dry-load never elevates trust | **PASS** | Lock notes + INV-008 descriptors |
| UNKNOWN cancel ⇒ worker-only | **PASS** (labels) | Matrices `measuredMode: UNKNOWN`; `CancellationModes.isPrivilegedSafe` |
| Design BASELINE + UNQUALIFIED allowed | **PASS** | Docs ENGINE-QUALIFICATION-STATUS §1 |

---

## 3. Per-engine matrix

### 3.1 `llama.cpp` (ENGINE-LLAMACPP)

| Dimension | Status | Evidence |
|---|---|---|
| **designStatus** | **PASS** BASELINE | Docs `docs/80-engines/llama-cpp.md`; `LlamaCppModule.DESIGN_STATUS = "BASELINE"` |
| **UPSTREAM.lock** | **PASS** LOCKED (impl) | `engines/llama-cpp/UPSTREAM.lock`: `lockState: LOCKED`, tag `b9999`, commit `47c78692…`, source/toolchain/artifact digests filled, `engineBuildId: llama-cpp-b9999-android` |
| **native/SDK present** | **PASS** | Vendored tree `android/native/src/main/cpp/third_party/llama.cpp` (CMakeLists + include/llama.h); built `libomnillm_llama.so` arm64-v8a (~82 MB) under `android/native/build/intermediates/...`; CMake links upstream when present (`OMNILLM_HAS_LLAMA_CPP`) |
| **registry attach** | **PASS** | `EnginePackAttachment.attachAfterReady` → `LlamaCppModule.registerWith` + `seedUnqualifiedPlaceholders` |
| **runtime exposure** | **PASS** honest UNKNOWN/UNQUALIFIED | Matrix + seed cells; projection never SUPPORTED without PASS |
| **executable path** | **PARTIAL** | Live `JniNativeBackend` when `.so` present; `EngineExecuteBinding` binds `LlamaCppInferenceEngineAdapter`; real GGUF path + `EXPERIMENTAL_FIXTURE` exploratory path; instrumented test source `RealLlamaUpstreamInstrumentedTest.kt` (engineering smoke — **not** QUALIFIED cell evidence pack) |
| **exploratory path** | **PASS** (honest label) | Fixture C++ loop `omnillm_fixture_backend.cpp` (“Not GGUF inference… NOT_SUPPORTED / UNQUALIFIED”); NATIVE.md load selection; dev CONDITIONAL projection |
| **honest UNQUALIFIED** | **PASS** | Lock notes L115–116; module `QUALIFICATION_STATUS = UNQUALIFIED`; matrix cells |

**L1:** PASS · **L2:** PASS · **L3 software:** PARTIAL (dev execute / fixture / emulator smoke possible; product SUPPORTED **BLOCKED_HUMAN** for full device/envelope matrix)  
**Overall software readiness:** PARTIAL  
**Qualification product claim:** **UNQUALIFIED** (must not claim SUPPORTED)

**Gaps**
- capability-matrix `upstreamLockStatus` stale (NOT_LOCKED) — ENG-DRIFT-01  
- NATIVE.md lock prose stale — ENG-DRIFT-02  
- No QUALIFIED_WITH_ENVELOPE + PASS cells in registry for any device×backend×model envelope  
- Device/OEM matrix evidence not in repo as formal qualification packages  

---

### 3.2 `LiteRT-LM` (ENGINE-LITERT)

| Dimension | Status | Evidence |
|---|---|---|
| **designStatus** | **PASS** BASELINE | Docs `litert-lm.md`; `LitertLmModule.DESIGN_STATUS` |
| **UPSTREAM.lock** | **PASS** LOCKED | `lockState: LOCKED`, tag `v0.15.0`, commit `2117fc43…`, AAR digest `b398c474…`, `engineBuildId: litert-lm-v0.15.0-android` |
| **native/SDK present** | **PARTIAL** | Module `compileOnly` + test `litertlm-jvm:0.15.0`; **no** `litertlm-android:0.15.0` on `:android:runtime-service` packaging; RealSdkBackend fails closed when AAR absent |
| **registry attach** | **PASS** (metadata) | `LitertLmModule.registerWith` + `seedUnqualifiedPlaceholders` in `registerPeerEngines` |
| **runtime exposure** | **PASS** UNKNOWN/UNQUALIFIED | Matrix + monorepo specs |
| **executable path** | **MISSING** (control plane) | No `createProductionEngine` call from `EnginePackAttachment`; not bound in `EngineExecuteBinding` |
| **exploratory path** | **PARTIAL** | Engine/adapter support exploratory flags in unit tests; not product-wired |
| **honest UNQUALIFIED** | **PASS** | specs evidenceNotes: “do not mark QUALIFIED/SUPPORTED” |

**L1:** PASS · **L2:** PARTIAL · **L3:** MISSING  
**Overall:** PARTIAL (adapter integrated; packaging + attach incomplete)  
**Qualification:** **UNQUALIFIED**

**Gaps**
- Package `litertlm-android:0.15.0` into runtime/companion (INV-001)  
- Wire production engine on attach when policy allows  
- Device evidence cells  

---

### 3.3 `MLC-LLM` (ENGINE-MLC)

| Dimension | Status | Evidence |
|---|---|---|
| **designStatus** | **PASS** BASELINE | Docs `mlc-llm.md`; `MlcLlmModule.DESIGN_STATUS` |
| **UPSTREAM.lock** | **PARTIAL** NOT_LOCKED (honest) | Commit pin `2f78caa4…` + sourceDigest; empty compiler/generated/artifact/toolchain digests; `engineBuildId: ""`; `lockState: NOT_LOCKED` |
| **native/SDK present** | **MISSING** | No Maven AAR; no in-tree `mlc4j` / `libtvm4j_runtime_packed.so`; reflective `MlcRuntimeBridge` fails closed when absent |
| **registry attach** | **PASS** (metadata) | `MlcLlmModule.registerWith` + `QualificationCells.seedUnqualified` |
| **runtime exposure** | **PASS** UNKNOWN/UNQUALIFIED | Matrix + load gated on complete lock |
| **executable path** | **MISSING** | No attach of `MlcEngineRuntimeBackend` on control plane |
| **exploratory path** | **PARTIAL** | StubRuntimeBackend tests; real backend factory exists but unused in attach |
| **honest UNQUALIFIED** | **PASS** | integrationNotes PENDING_QUALIFICATION |

**L1:** PASS · **L2:** PARTIAL · **L3:** MISSING  
**Overall:** PARTIAL (code binding present; supply chain incomplete)  
**Qualification:** **UNQUALIFIED**

**Gaps**
- Human pin: `mlc_llm package` → fill digests → `lockState: LOCKED`  
- Ship generated runtime into app module  
- Device/OpenCL matrix  

---

### 3.4 `mllm` (ENGINE-MLLM)

| Dimension | Status | Evidence |
|---|---|---|
| **designStatus** | **PASS** BASELINE | Docs `mllm.md`; `MllmModule.DESIGN_STATUS` |
| **UPSTREAM.lock** | **PASS** LOCKED | tag `2.0.0`, commit `c67485a3…`, digests filled, `engineBuildId: mllm-2.0.0-gomllm-aar-arm64` |
| **native/SDK present** | **PASS** (packaged in engine module) | Tracked `engines/mllm/libs/mllm_server.aar` (~5.0 MB); build extracts `libgojni.so` + fetch-verified jniLibs: `libMllmRT.so` (~146 MB), `libMllmCPUBackend.so`, `libMllmSdkC.so`, `libomp.so` arm64-v8a; QNN excluded (4 KB align) |
| **registry attach** | **PASS** (metadata only on control plane) | `MllmModule.registerWith` + seed; comment “no AAR load” at attach |
| **runtime exposure** | **PASS** UNKNOWN/UNQUALIFIED | Matrix cells all UNQUALIFIED |
| **executable path** | **PARTIAL** | `MllmServerBackend` / `GomllmServerBridge` real code default in module factory; **not** bound by `EngineExecuteBinding` / attachAfterReady |
| **exploratory path** | **PARTIAL** | StubServerBackend tests; real backend host-safe construct; unproven ops fail closed without complete lock (lock is complete → allowUnproven defaults true in module factory) |
| **honest UNQUALIFIED** | **PASS** | Lock notes L131; no unload/embed APIs → UNSUPPORTED_OPERATION honesty |

**L1:** PASS · **L2:** PARTIAL · **L3:** MISSING (product path)  
**Overall:** PARTIAL  
**Qualification:** **UNQUALIFIED**

**Gaps**
- Live attach + multi-engine execute binding for mllm  
- Private-channel auth/device evidence; prompt digest resolution Stage-5  
- Formal qualification cells  

---

### 3.5 `ONNX-Runtime-GenAI` (ENGINE-ORTGENAI)

| Dimension | Status | Evidence |
|---|---|---|
| **designStatus** | **PASS** BASELINE | Docs `onnx-runtime-genai.md`; `OrtGenaiModule.DESIGN_STATUS` |
| **UPSTREAM.lock** | **PASS** LOCKED | `v0.14.0` commit `b7a6ec30…`, AAR digest `c2e9b967…`, ORT base 1.25.1, `engineBuildId: ort-genai-0.14.0-aar-c2e9b967` |
| **native/SDK present** | **PARTIAL** | `libs/onnxruntime-genai-android-0.14.0.jar` = classes.jar only (~26 KB, compileOnly); full Android AAR + `onnxruntime-android` **not** on runtime-service classpath (lock notes Stage 5 packaging) |
| **registry attach** | **PASS** (metadata) | `OrtGenaiModule.registerDesignCompleteUnqualified` |
| **runtime exposure** | **PASS** UNKNOWN/UNQUALIFIED | Matrix INTEGRATED ≠ SUPPORTED |
| **executable path** | **MISSING** (control plane) | RealGenAiBackend exists; not attached/bound on production path |
| **exploratory path** | **PARTIAL** | Unit tests with `allowExploratoryExecute`; host JVM cannot load Android `.so` (honest NOT_AVAILABLE) |
| **honest UNQUALIFIED** | **PASS** | capability-matrix knownLimitations; lock failClosed note |

**L1:** PASS · **L2:** PARTIAL · **L3:** MISSING  
**Overall:** PARTIAL  
**Qualification:** **UNQUALIFIED**

**Gaps**
- Package GenAI AAR + base ORT AAR into runtime process; verify 16 KB + jni merge  
- Control-plane attach + execute binding  
- Device inference evidence  

---

## 4. Exploratory / fixture honesty

| Path | Status | Notes |
|---|---|---|
| llama EXPERIMENTAL_FIXTURE | **PASS** honest | C++ fixture not claimed as GGUF SUPPORTED; explicit markers required; no silent fixture for broker-only keys |
| Dev ship CONDITIONAL | **PASS** honest | Explicit conditions; never plain SUPPORTED without PASS |
| Peer stubs | **PASS** fail-closed | Production attach does not silent-stub peers as success; missing natives → not load-success |
| Dashboard AllSupportedCapabilityPort | **N_A** to engine cells | Out of ENGINE pack matrix (product audit already flagged); does **not** write engine QUALIFIED cells |

---

## 5. Qualification status YAML comparison

| engineId | Docs package lock | Impl lock (UPSTREAM + monorepo specs) | qualification | registryExposure |
|---|---|---|---|---|
| llama.cpp | NOT_LOCKED | **LOCKED** | UNQUALIFIED | UNKNOWN |
| LiteRT-LM | NOT_LOCKED | **LOCKED** | UNQUALIFIED | UNKNOWN |
| MLC-LLM | NOT_LOCKED | **NOT_LOCKED** | UNQUALIFIED | UNKNOWN |
| mllm | NOT_LOCKED | **LOCKED** | UNQUALIFIED | UNKNOWN |
| ONNX-Runtime-GenAI | NOT_LOCKED | **LOCKED** | UNQUALIFIED | UNKNOWN |

Docs package remains valid as **pre-build design** authority; implementation has advanced supply-chain locks without elevating qualification (correct dual-axis).

---

## 6. Aggregate verdict

| Engine | design | lock | native | executable (CP) | honest UNQUALIFIED | Overall |
|---|---|---|---|---|---|---|
| llama.cpp | PASS | PASS (LOCKED) | PASS | PARTIAL | PASS | **PARTIAL** |
| LiteRT-LM | PASS | PASS (LOCKED) | PARTIAL | MISSING | PASS | **PARTIAL** |
| MLC-LLM | PASS | PARTIAL (NOT_LOCKED) | MISSING | MISSING | PASS | **PARTIAL** |
| mllm | PASS | PASS (LOCKED) | PASS | PARTIAL | PASS | **PARTIAL** |
| ONNX-Runtime-GenAI | PASS | PASS (LOCKED) | PARTIAL | MISSING | PASS | **PARTIAL** |

**Catalog matrix software status: PARTIAL**  
**Any engine QUALIFIED/SUPPORTED: NO (correct)**  
**False SUPPORTED/QUALIFIED CRITICAL: 0**  
**Device formal qualification: BLOCKED_HUMAN** (Stage 5 evidence packs not present as registry PASS cells)

---

## 7. What was searched (empty-finding discipline)

Grep / read covered:

- `engines/**` UPSTREAM.lock, capability-matrix.yaml, Module/Engine/Backend Kotlin  
- `engines/api/**` EngineRegistry, labels, OmniEngine  
- `android/runtime-service/**` EnginePackAttachment, EngineSelectionPolicy, EngineExecuteBinding, RealLlamaUpstreamInstrumentedTest  
- `android/native/**` CMake, fixture, third_party llama.cpp presence  
- `specs/engine-qualification-status.yaml` (docs + monorepo)  
- `settings.gradle.kts` engine includes  
- Patterns: `QUALIFIED_WITH_ENVELOPE`, `CapabilityState.SUPPORTED`, `SUPPORTED`, `createProductionEngine`, AAR packaging coords  

No sixth engine pack directory or engineId outside the five catalog IDs was found.

---

## 8. Recommended closeouts (informational; not in scope to implement)

1. Fix ENG-DRIFT-01/02 (llama matrix + NATIVE.md lock prose).  
2. Either wire peer live backends on attach under dev mode **or** correct `engine-registry-attachment.md` to say “registry-only until E3–E6”.  
3. Package LiteRT + ORT Android AARs into `:android:runtime-service` (or worker) before claiming device smoke.  
4. Complete MLC human pin checklist.  
5. Stage 5: formal evidence packs → QUALIFIED_WITH_ENVELOPE + PASS only after measured envelopes — never auto-promote from lock or emulator smoke alone.
