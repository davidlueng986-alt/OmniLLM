# OmniLLM Android — Feature & Engine Software Audit

**As-of:** 2026-08-06  
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
| **FEAT-LAN** | `docs/70-features/lan-access.md` | **PASS** | `features/lan` + `ControlPlaneLanHost` / TLS endpoint; durable pairing + tokens; default-off bind; UI LAN via Server/LAN destinations. Residual: real LAN device pairing + cert trust UX on hardware. |
| **FEAT-DASHBOARD** | `docs/70-features/dashboard-monitoring.md` | **PASS** | `features/dashboard` + UI primary tab; health/metrics with evidence labels via observability. Residual: long-lived live charts polish. |
| **FEAT-BENCHMARK** | `docs/70-features/benchmark-research.md` | **PASS** | `features/benchmark` (21 main KT, 4 tests); hosted wave-B; UI `BenchmarkScreen` (drawer). Residual: measured device numbers not invented; jobs durable but real load depends on engine. |
| **FEAT-DIAGNOSTICS** | `docs/70-features/diagnostics-export.md` | **PASS** | `features/diagnostics`; allowlist-first export + integrity seal; UI `DiagnosticsScreen`; tests cover redaction (payload vs allowlist schema). Residual: share intent / SAF on device. |
| **FEAT-ROUTING** | `docs/70-features/multi-model-routing.md` | **PASS** | `features/routing` + Orchestrator candidate planner; UI `RoutingScreen`; fail-closed / no silent cross-revision fallback. Residual: multi-engine live routing limited by peer engine stubs. |
| **FEAT-TOOLS** | `docs/70-features/structured-tools.md` | **PARTIAL** | Domain `features/tools` (17 main, 6 tests) + durable proposal ledger on plane + playground tools/structured tabs + HTTP tools API. **No dedicated Tools top-level destination** (embedded in Playground / HTTP). Residual: full tool sandbox companion depth on device. |
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

**Global rule (verified):** No engine cell is marked **QUALIFIED** or projected **SUPPORTED** without evidence. Design may be BASELINE while every runtime cell remains UNQUALIFIED.

| ID | engineId | Design (docs) | Software status | Qualification honesty | Notes |
|---|---|---|---|---|---|
| **ENGINE-STANDARD** | — | BASELINE | **PASS** | N/A | SPI in `engines/api`; registry, placement, FakeEngine tests. |
| **ENGINE-LLAMACPP** | `llama.cpp` | BASELINE | **PASS** (adapter + packaging) / **PARTIAL** (upstream) | **UNQUALIFIED / UNKNOWN** | Adapter + Plan/Reserve/Commit/Execute pipeline tests; JNI `libomnillm_llama` packaged (experimental fixture / optional vendored tree); production bind via `EngineExecuteBinding` when `.so` present. `UPSTREAM.lock` **NOT_LOCKED**. Exploratory execute → **CONDITIONAL** only if `runtime.exploratoryExecuteEnabled`. |
| **ENGINE-LITERT** | `LiteRT-LM` | BASELINE | **PARTIAL** | **UNQUALIFIED / UNKNOWN** | Adapter module + pipeline/stub tests; registry stub on attach; **no** production native/SDK load. |
| **ENGINE-MLC** | `MLC-LLM` | BASELINE | **PARTIAL** | **UNQUALIFIED / UNKNOWN** | Adapter + exploratory stub; no production native. |
| **ENGINE-MLLM** | `mllm` | BASELINE | **PARTIAL** | **UNQUALIFIED / UNKNOWN** | Adapter + private channel scaffold; no production native. |
| **ENGINE-ORTGENAI** | `ONNX-Runtime-GenAI` | BASELINE | **PARTIAL** | **UNQUALIFIED / UNKNOWN** | Adapter stub; no ORT/GenAI natives packaged. |
| **ENGINE-QUALIFICATION-STATUS** | — | BASELINE | **PASS** | Spec honest | `specs/engine-qualification-status.yaml` all UNQUALIFIED/UNKNOWN; matrices match; attach asserts no SUPPORTED cells without evidence. |

### Engine software vs device evidence

| Layer | Software | Device / human |
|---|---|---|
| Registry registration after READY | Implemented | — |
| Placeholder cells UNQUALIFIED + NOT_EXECUTED | Implemented | — |
| Native packaging 16 KB / ABI | Tooling + llama shim | Full multi-device matrix |
| PASS evidence packs | Not invented | Lab / OEM only |
| SUPPORTED projection | Impossible without QUALIFIED_WITH_ENVELOPE + PASS | Human evidence workflow |

---

## Summary counts

| Category | PASS | PARTIAL | MISSING |
|---|---|---|---|
| FEAT-* (12) | 11 | 1 (TOOLS depth/UI) | 0 |
| ENGINE-* packs (5) | 1 software-primary (llama-cpp adapter path) + 4 partial stubs | (counted under PARTIAL) | 0 |
| ENGINE-STANDARD / QUALIFICATION-STATUS | 2 PASS | — | — |

**Software readiness alignment:** Matches `PRODUCT_READINESS.md` → software ready **YES**, with honest UNQUALIFIED engines and human residuals for device, Play, and upstream locks.

---

## Verification commands used

```text
.\gradlew.bat test --continue
.\gradlew.bat :android:app-ui:assembleDebug :android:app-ui:assembleRelease ^
  :android:companion-sandbox:assembleDebug :android:companion-sandbox:assembleRelease
```

Grep / read checks: `FailClosedInferenceEngine`, `InMemoryJobStore` / `InMemorySecretBroker` / `InMemoryClaim*`, `specs/engine-qualification-status.yaml`, production `RuntimeControlPlane.attach`.
