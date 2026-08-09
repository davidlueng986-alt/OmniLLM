# Engine Registry attachment (RuntimeControlPlane)

**Owner:** `:android:runtime-service` control plane  
**Authority (implementation):** `ProductBuildMode`, `EnginePackAttachment`, `EngineSelectionPolicy`, `EngineExecuteBinding`  
**Code:** `RuntimeControlPlane.ensureEnginePacksAttached`

> **Development posture (current):** `ProductBuildMode.DEVELOPMENT_SHIP_MODE = true`.  
> Goal is **finish all engines + features**. Qualification paperwork and “no lock ⇒ no execute” gates do **not** block develop/execute.  
> Flip `DEVELOPMENT_SHIP_MODE = false` only for a compliance honesty audit.

## When attach runs

- Only in the `:runtime` process (INV-001 — UI never loads native).
- Only after RUNTIME lifecycle is **READY** or **DEGRADED** (not during RECOVERING).
- Idempotent: first successful attach is retained on `RuntimeControlPlane.enginePacks`.

## Catalog engines (all registered)

| engineId | Gradle module | Backend on attach (DEV ship mode) |
|---|---|---|
| `llama.cpp` | `:engines:llama-cpp` | Real `JniNativeBackend` via `libomnillm_llama` when present |
| `LiteRT-LM` | `:engines:litert-lm` | Adapter + SDK SPI (wire real SDK when available) |
| `MLC-LLM` | `:engines:mlc-llm` | Adapter + runtime SPI (wire real MLC when available) |
| `mllm` | `:engines:mllm` | Adapter + server/AAR SPI |
| `ONNX-Runtime-GenAI` | `:engines:ort-genai` | Adapter + GenAI SPI |

Each pack registers:

1. `EngineRegistration` (build metadata; incomplete UPSTREAM.lock is allowed in DEV)
2. Phase-capability cells (seeded from matrix; DEV mode does not require PASS to execute)

## Selection policy (current)

Implemented as `EngineSelectionPolicy` + `ProductBuildMode`.

### DEVELOPMENT_SHIP_MODE = true (default)

1. **All catalog engines** may use real native/SDK when wired (`nativeEligible = ALL_CATALOG`).
2. **Execute does not require** `QUALIFIED_WITH_ENVELOPE` + lab PASS.
3. Bound adapter ⇒ generation path may project **SUPPORTED** / run via Orchestrator (`EngineExecuteBinding`).
4. Attach does **not** assert-fail on SUPPORTED projection.
5. `runtime.exploratoryExecuteEnabled` defaults **true**.
6. Transports still do not select engines (ADR-011) — Orchestrator + Registry own routing.
7. Missing backend / missing model still fails with a clear error (implementation gap ≠ policy gate).

### COMPLIANCE_HONESTY_MODE (DEVELOPMENT_SHIP_MODE = false)

Restores old pre-build document rules:

1. Executable only when cell projects SUPPORTED.
2. SUPPORTED only from QUALIFIED_WITH_ENVELOPE + evidence PASS.
3. Only llama-cpp native-eligible; peers stub/UNKNOWN.
4. Fail closed on UNKNOWN; attach asserts no invented SUPPORTED cells.

## What attach deliberately does not do

- Load peer engine vendor SDKs that are **not packaged** yet (those are **implementation TODOs**, not policy blocks)
- Write OmniLLM domain DB from engine adapters (ADR-010)
- Claim Play-store “qualified on device matrix” without real evidence (lab PASS is separate from develop mode)

## Tests

- `:android:runtime-service` → `EnginePackAttachmentTest` (update expectations for DEV mode)
- `:engines:api` → `EngineRegistryTest` (projection pure rules still apply for compliance path)

## Wiring

```
RuntimeControlPlane.attach
  → WaveAWiring.wire(engineExecute = EngineExecuteBinding)
       Orchestrator ← DelegatingInferenceEngine
       Playground/Server/Tools/Routing ports ← Orchestrator path
  → ensureStarted → READY|DEGRADED
  → ensureEnginePacksAttached
  → EnginePackAttachment.attachAfterReady
  → EngineRegistry + catalog engines (native/SDK when present)
  → EngineExecuteBinding.applyAttachment
       DelegatingInferenceEngine.bind(...)
```

### Execute path (DEV ship mode)

| Condition | Result |
|---|---|
| Adapter bound + model READY path | Execute allowed (stream tokens when backend real) |
| Backend missing / stub only | Clear error / stub response — **fix by wiring real SDK** |
| Model not imported | Fail with model-not-ready (feature work: hub import/load) |
| COMPLIANCE mode + no PASS cell | Fail closed UNKNOWN |

Setting: `runtime.exploratoryExecuteEnabled` defaults from `ProductBuildMode`  
(`ConfigurationCatalog` / `specs/configuration-catalog.yaml`).

**Master ship list:** root `SHIP_BACKLOG.md` (what still must be built vs policy).

Gradle: `:android:runtime-service` depends on `:engines:api` and every catalog Engine Pack module; `:android:native` supplies `libomnillm_llama` for the runtime process only.
