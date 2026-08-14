# OmniLLM Android — Feature & Engine Software Audit

**As-of:** 2026-08-09 (Stage 4 launch-readiness refresh)  
**Repo:** `omnillm-android/`  
**Docs authority:** `OmniLLM_Product_Documents/docs/70-features`, `docs/80-engines`, `specs/feature-capability-map.yaml`, `specs/engine-qualification-status.yaml`  
**Scope:** Software implementation vs product design — **not** device PASS / OEM matrix.

### Legend

| Status | Meaning |
|---|---|
| **PASS** | Domain module + control-plane host + tests + primary UX/API path present; honest capability projection |
| **PARTIAL** | Core software present but missing depth (UI surface, native backend, durability secondary, or doc capability subset) |
| **MISSING** | No meaningful software surface for the design |

---

## FEAT-* (Feature Packs)

All 12 packs are **constructed on the control plane** (`WaveAWiring` + `FeaturePackHost` in `RuntimeControlPlane.attach`). Host unit tests exercise domain logic.

| ID | Docs | Software status | Evidence / residual |
|---|---|---|---|
| **FEAT-AUTOSETUP** | `docs/70-features/auto-setup.md` | **PASS** | Module `features/auto-setup` (17 main KT, 4 tests); hosted in wave-A; UI `OnboardingScreen` / Setup drawer; plan/recommend paths; no invented engine SUPPORTED. Residual: full device discovery fidelity is host/device-dependent. |
| **FEAT-MODELHUB** | `docs/70-features/modelhub-acquisition.md` | **PASS** | `features/modelhub` + durable model manager / model-store FS on plane; UI `ModelHubScreen` (primary tab); acquisition jobs via durable `JobManager`. Residual: display/link ports may still be process-memory helpers (`WaveAWiring`); network download policy present, real large-file UX needs device. |
| **FEAT-PLAYGROUND** | `docs/70-features/local-playground.md` | **PASS** (software) | `features/playground` + UI `PlaygroundScreen`; routes through Orchestrator + `EngineExecuteBinding`. Generate is fail-closed until exploratory flag + native; tools/structured tabs exist in playground. Residual: vision/audio capability UNKNOWN without evidence; real GGUF quality human-only. |
| **FEAT-SERVER** | `docs/70-features/developer-server.md` | **PASS** | `features/server` + loopback HTTP gateway (`GatewayLifecycle` / `ControlPlaneHttpHandler`); UI Server tab; capability negotiation fail-closed on UNKNOWN. Residual: OpenAPI surface breadth vs full catalog may be partial on edge routes. |
| **FEAT-LAN** | `docs/70-features/lan-access.md` | **PASS** | `features/lan` + `ControlPlaneLanHost` / TLS endpoint; durable pairing + tokens; default-off bind; dedicated `OmniDestination.Lan` (nav + screen). Residual: real LAN device pairing + cert trust UX on hardware. |
| **FEAT-DASHBOARD** | `docs/70-features/dashboard-monitoring.md` | **PASS** | `features/dashboard` + UI primary tab; health/metrics with evidence labels via observability. Residual: long-lived live charts polish. |
| **FEAT-BENCHMARK** | `docs/70-features/benchmark-research.md` | **PASS** | `features/benchmark` (21 main KT, 4 tests); hosted wave-B; dedicated `OmniDestination.Benchmark` + `BenchmarkScreen`; jobs durable. Residual: measured device numbers not invented; real load depends on engine qualification. |
| **FEAT-DIAGNOSTICS** | `docs/70-features/diagnostics-export.md` | **PASS** | `features/diagnostics`; allowlist-first export + integrity seal; UI `DiagnosticsScreen`; tests cover redaction (payload vs allowlist schema). Residual: share intent / SAF on device. |
| **FEAT-ROUTING** | `docs/70-features/multi-model-routing.md` | **PASS** | `features/routing` + Orchestrator candidate planner; dedicated `OmniDestination.Routing` + `RoutingScreen`; fail-closed / no silent cross-revision fallback. Residual: multi-engine live routing limited by device qualification (all engines UNQUALIFIED). |
| **FEAT-TOOLS** | `docs/70-features/structured-tools.md` | **PARTIAL** | Domain `features/tools` (17 main, 6 tests) + **durable** proposal/claim ledger on plane (`ToolProposals.sq`/`ToolResultClaims.sq` + `SqlDelightToolProposalStore`) + playground tools/structured tabs + HTTP tools API. **No dedicated Tools top-level destination** (SW-UI-03 OPEN; embedded in Playground / HTTP). Residual: full tool sandbox companion depth on device. |
| **FEAT-ADMIN** | `docs/70-features/administration-jobs.md` | **PASS** | `features/admin` + AIDL `IOmniAdmin` binder factory for UI; settings/jobs/snapshot; LOCAL_UI principal. Residual: every admin command projection vs full catalog. |
| **FEAT-AI-CONTENT-REPORT** | `docs/70-features/ai-content-reporting.md` | **PASS** | `features/ai-content-report` + durable ledger on plane; UI `ContentReportScreen`. Residual: store/regulatory questionnaire is human Play work. |

### Cross-cutting feature software (not separate FEAT-IDs)

| Area | Status | Notes |
|---|---|---|
| INV-001 UI isolation | **PASS** | `app-ui` has no native load / no DB writer; admin via binder |
| ADR-010 single writer | **PASS** | Control plane only durable writer |
| Plan→Reserve→Commit→Execute | **PASS** | Orchestrator + engine Plan pure; tests in engines + runtime |
| Observability / redaction | **PASS** | `runtime/observability` + diagnostics export |
| Companion sandbox | **PASS** (software) | Separate APK assemble green; packaging docs present |

---

## ENGINE-* (Engine Packs)

Authority: `docs/80-engines/*`, `specs/engine-qualification-status.yaml`, per-module `capability-matrix.yaml`, `UPSTREAM.lock`.

**Build posture (BLD-02):** variant-scoped `ProductBuildMode` — debug/dev builds get dev-ship semantics via per-buildType `BuildConfig` (`OMNILLM_DEV_SHIP_MODE`), **release is always fail-closed** (`ProductBuildMode.FAIL_CLOSED`). Develop/execute is **not** blocked by lab QUALIFIED/PASS in dev builds; lab cells stay UNQUALIFIED — that is honest bookkeeping, not a “stop coding” rule. Dev-mode capability projection stays **CONDITIONAL** (explicit `development_ship_mode` condition), never fake SUPPORTED (COR-10).
**What still blocks “engines work”:** device-verified inference evidence (all 5 engines are integrated with real backends but UNQUALIFIED) — see root **`SHIP_BACKLOG.md`**.

| ID | engineId | Design (docs) | Software status | Runtime backend (fact) | Upstream lock / integration |
|---|---|---|---|---|---|
| **ENGINE-STANDARD** | — | BASELINE | **PASS** | N/A | SPI in `engines/api`; registry, placement, FakeEngine tests. |
| **ENGINE-LLAMACPP** | `llama.cpp` | BASELINE | **PASS (implementation)** | Real JNI `libomnillm_llama` (vendored b9999, upstream-linked; EXPERIMENTAL_FIXTURE loop always present) | **LOCKED** (b9999/47c7869, digests filled; artifactDigest stripped-packaged, D2 `756f5e1`). Real GGUF verified on emulator (`RealLlamaUpstreamInstrumentedTest`). Still UNQUALIFIED. |
| **ENGINE-LITERT** | `LiteRT-LM` | BASELINE | **PASS (implementation)** | `OfficialLitertLmSdkBridge` (typed official SDK, compile-verified); **C-07 (`1bd00fd`): attached LIVE on control plane when policy + SDK present** (`EnginePackAttachment.registerPeerEngines`), else metadata-only | **LOCKED** (v0.15.0) / `INTEGRATED_PENDING_QUALIFICATION`. No device inference yet; attachability ≠ qualification. |
| **ENGINE-MLC** | `MLC-LLM` | BASELINE | **PASS (implementation)** | `MlcEngineRuntimeBackend` binding generated mlc4j runtime; **metadata-only on plane (C-07 decision)** | **NOT_LOCKED** (pin 2f78caa4) / `INTEGRATED` — load fail-closed until complete lock. |
| **ENGINE-MLLM** | `mllm` | BASELINE | **PASS (implementation)** | `MllmServerBackend` (gomllm in-app server, loopback HTTP/SSE); **D3 (`6b1af0c`): post-start identity probe — SERVER_CRASH/SERVER_IMPERSONATED fail-closed; metadata-only on plane (C-07)** | **LOCKED** (2.0.0/c67485a3) / `INTEGRATED_PENDING_QUALIFICATION`. Residual: upstream server has no auth (D4, adapter-enforced credentials; see threat-model). |
| **ENGINE-ORTGENAI** | `ONNX-Runtime-GenAI` | BASELINE | **PASS (implementation)** | `RealGenAiBackend` over onnxruntime-genai AAR Java API; **C-07 (`1bd00fd`): attached LIVE on control plane when policy + API present**, else metadata-only | **LOCKED** (0.14.0) / `INTEGRATED`. No device inference yet; attachability ≠ qualification. |
| **ENGINE-QUALIFICATION-STATUS** | — | BASELINE | **PASS** (spec) | — | `integrationStatus`/`evidenceNotes` formalized in schema (Stage 4); `controlPlaneAttach` added (C-07); dev builds ignore PASS for execute. |

### Engine software vs “done”

| Layer | Status | Action to finish |
|---|---|---|
| Registry registration after READY | Done | — |
| Dev execute without PASS packs | Done (`ProductBuildMode`, variant-scoped) | — |
| Real llama GGUF generate | **Done** (emulator-verified) | Device matrix / envelope evidence |
| Peer engines real backend | **Done (implementation)** — litert/ort/mllm/mlc real bindings | Device qualification (Stage 5) |
| Playground/HTTP stable tokens | Done (SSE chat + real adapter; COR-03/04) | Device stability on matrix |
| Lab PASS / multi-device matrix | Optional for claims | Human later; not develop gate |

---

## Summary counts

| Category | Scaffold/host | True product depth |
|---|---|---|
| FEAT-* (12) | 12 modules + control plane host | Main journeys mostly present; device depth + Tools destination open (SHIP_BACKLOG F*, UI) |
| ENGINE-* packs (5) | 5 **real** backends (llama real GGUF on emulator; litert/ort/mllm/mlc real bindings) | **0/5** have device-verified PASS cells — UNQUALIFIED by design until Stage 5 |
| Policy / honesty gates | Dev builds unblocked; **release fail-closed** | `ProductBuildMode` variant-scoped (BLD-02); projections stay CONDITIONAL in dev (COR-10) |

**Alignment:** Scaffold software packaging is strong; **feature/engine completion for ship** is tracked only in **`SHIP_BACKLOG.md`** (not “UNQUALIFIED = stop”).

---

## Verification commands used

```text
.\gradlew.bat test --continue
.\gradlew.bat :android:app-ui:assembleDebug :android:app-ui:assembleRelease ^
  :android:companion-sandbox:assembleDebug :android:companion-sandbox:assembleRelease
```

Grep / read checks: `FailClosedInferenceEngine`, `InMemoryJobStore` / `InMemorySecretBroker` / `InMemoryClaim*`, `specs/engine-qualification-status.yaml`, production `RuntimeControlPlane.attach`.
