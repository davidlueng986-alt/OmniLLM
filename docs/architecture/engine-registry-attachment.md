# Engine Registry attachment (RuntimeControlPlane)

**Owner:** `:android:runtime-service` control plane  
**Authority (implementation):** `ProductBuildMode`, `EnginePackAttachment`, `EngineSelectionPolicy`, `EngineExecuteBinding`  
**Code:** `RuntimeControlPlane.ensureEnginePacksAttached`

> **Development posture (current, BLD-02):** `ProductBuildMode` is **variant-scoped and injected** — there is no global `DEVELOPMENT_SHIP_MODE` const anymore.
> - **Debug/dev builds**: per-buildType `BuildConfig.OMNILLM_DEV_SHIP_MODE=true` (auditable override `-Pomnillm.developmentShipMode`). Goal is **finish all engines + features**; qualification paperwork and “no lock ⇒ no execute” gates do **not** block develop/execute.
> - **Release builds**: always `ProductBuildMode.FAIL_CLOSED` (dev semantics OFF by default; nothing in the codebase flips it on for release).
> - Dev mode is **not** a license to claim SUPPORTED: capability projection stays **CONDITIONAL** with an explicit `development_ship_mode` condition (COR-10 / INV-018/019).

## When attach runs

- Only in the `:runtime` process (INV-001 — UI never loads native).
- Only after RUNTIME lifecycle is **READY** or **DEGRADED** (not during RECOVERING).
- Idempotent: first successful attach is retained on `RuntimeControlPlane.enginePacks`.

## Catalog engines (all registered)

| engineId | Gradle module | Backend on attach (dev build) | Lock / integration (specs) |
|---|---|---|---|
| `llama.cpp` | `:engines:llama-cpp` | Real JNI `libomnillm_llama` (vendored b9999, upstream-linked; EXPERIMENTAL_FIXTURE loop always present) | LOCKED / real GGUF verified on emulator; UNQUALIFIED |
| `LiteRT-LM` | `:engines:litert-lm` | `OfficialLitertLmSdkBridge` (typed official SDK, compile-verified) | LOCKED v0.15.0 / INTEGRATED_PENDING_QUALIFICATION |
| `MLC-LLM` | `:engines:mlc-llm` | `MlcEngineRuntimeBackend` binding generated mlc4j runtime | NOT_LOCKED (pin) / INTEGRATED — load fail-closed until complete lock |
| `mllm` | `:engines:mllm` | `MllmServerBackend` (gomllm in-app server, loopback HTTP/SSE) | LOCKED 2.0.0 / INTEGRATED_PENDING_QUALIFICATION |
| `ONNX-Runtime-GenAI` | `:engines:ort-genai` | `RealGenAiBackend` over onnxruntime-genai AAR Java API | LOCKED 0.14.0 / INTEGRATED |

All five remain **UNQUALIFIED** — real backend wiring is not device-verified inference
evidence. `specs/engine-qualification-status.yaml` is the single source of truth for
lock/integration/qualification state (FTR-04; schema formalizes `integrationStatus` +
`evidenceNotes`).

Each pack registers:

1. `EngineRegistration` (build metadata from `UPSTREAM.lock`; a complete lock is a supply-chain requirement, not a SUPPORTED claim)
2. Phase-capability cells (seeded from matrix; dev builds do not require PASS to execute)

## Selection policy (current)

Implemented as `EngineSelectionPolicy` + `ProductBuildMode` (variant-scoped, BLD-02).

### Dev build (developmentShipMode = true)

1. **All catalog engines** may use real native/SDK when wired (`nativeEligible = ALL_CATALOG`).
2. **Execute does not require** `QUALIFIED_WITH_ENVELOPE` + lab PASS.
3. Bound adapter ⇒ generation path may run via Orchestrator (`EngineExecuteBinding` → `DelegatingInferenceEngine`); capability projection is **CONDITIONAL with `development_ship_mode` condition** — never plain SUPPORTED without PASS (COR-10).
4. Attach does **not** assert-fail on SUPPORTED projection (dev builds only).
5. `runtime.exploratoryExecuteEnabled` product-default seeds **true** from build mode at runtime; the static `ConfigurationCatalog` stays fail-closed.
6. Transports still do not select engines (ADR-011) — Orchestrator + Registry own routing.
7. Missing backend / missing model still fails with a clear error (implementation gap ≠ policy gate).

### Release / compliance posture (developmentShipMode = false — FAIL_CLOSED default)

Restores the fail-closed document rules:

1. Executable only when cell projects SUPPORTED.
2. SUPPORTED only from QUALIFIED_WITH_ENVELOPE + evidence PASS.
3. Only llama-cpp native-eligible; peers UNKNOWN/fail-closed.
4. Fail closed on UNKNOWN; attach asserts no invented SUPPORTED cells.

## What attach deliberately does not do

- Claim device-verified capability for engines without evidence packs (all cells stay UNQUALIFIED; llama has one emulator smoke data point, not a matrix)
- Write OmniLLM domain DB from engine adapters (ADR-010)
- Claim Play-store “qualified on device matrix” without real evidence (lab PASS is separate from dev mode)

## Tests

- `:android:runtime-service` → `EnginePackAttachmentTest` / `EngineExecuteBindingTest` (mode-aware expectations; `attachForTest`/`registerPeerEngines` parameterized by `ProductBuildMode`)
- `:engines:api` → `EngineRegistryTest` (projection pure rules still apply for compliance path)
- `RealLlamaUpstreamInstrumentedTest` — real GGUF generate on emulator (connected test)

## Wiring

```
RuntimeControlPlane.attach
  → WaveAWiring.wire(engineExecute = EngineExecuteBinding)
       Orchestrator ← DelegatingInferenceEngine (binding.inferenceEngine)
       Playground/Server/Tools/Routing ports ← Orchestrator path
  → ensureStarted → READY|DEGRADED
  → ensureEnginePacksAttached
  → EnginePackAttachment.attachAfterReady
  → EngineRegistry + catalog engines (native/SDK when present)
  → EngineExecuteBinding.applyAttachment
       DelegatingInferenceEngine.bind(...)
```

### Execute path (dev build)

| Condition | Result |
|---|---|
| Adapter bound + model READY path | Execute allowed (llama: real in-process GGUF; peers: real backend when model present) |
| Backend unbound | Fail-closed UNKNOWN / CAPABILITY_UNSUPPORTED (honest; not a stub response) |
| Model not imported | Fail with model-not-ready (feature work: hub import/load) |
| Release build / compliance posture + no PASS cell | Fail closed UNKNOWN |

Setting: `runtime.exploratoryExecuteEnabled` defaults from `ProductBuildMode`  
(`ConfigurationCatalog` / `specs/configuration-catalog.yaml`).

**Master ship list:** root `SHIP_BACKLOG.md` (what still must be built vs policy).

Gradle: `:android:runtime-service` depends on `:engines:api` and every catalog Engine Pack module; `:android:native` supplies `libomnillm_llama` for the runtime process only.
