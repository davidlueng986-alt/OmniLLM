# Engine Registry attachment (RuntimeControlPlane)

**Owner:** `:android:runtime-service` control plane  
**Authority:** ENGINE-STANDARD, ENGINE-QUALIFICATION-STATUS, ADR-010, INV-001, INV-018  
**Code:** `EnginePackAttachment`, `EngineSelectionPolicy`, `RuntimeControlPlane.ensureEnginePacksAttached`

## When attach runs

- Only in the `:runtime` process (INV-001 — UI never loads native).
- Only after RUNTIME lifecycle is **READY** or **DEGRADED** (not during RECOVERING).
- Idempotent: first successful attach is retained on `RuntimeControlPlane.enginePacks`.

## Catalog engines (all registered)

| engineId | Gradle module | Production backend on attach |
|---|---|---|
| `llama.cpp` | `:engines:llama-cpp` | Real `JniNativeBackend` via `libomnillm_llama` when present; **no** silent `StubNativeBackend` substitute |
| `LiteRT-LM` | `:engines:litert-lm` | Registry stub only (UNKNOWN) |
| `MLC-LLM` | `:engines:mlc-llm` | Registry stub only (UNKNOWN) |
| `mllm` | `:engines:mllm` | Registry stub only (UNKNOWN) |
| `ONNX-Runtime-GenAI` | `:engines:ort-genai` | Registry stub only (UNKNOWN) |

Each pack registers:

1. `EngineRegistration` (build metadata; incomplete UPSTREAM.lock is allowed)
2. Phase-capability **placeholder cells** at `UNQUALIFIED` + evidence `NOT_EXECUTED`

## Selection policy (normative)

Implemented as `EngineSelectionPolicy` (see KDoc for the full rule list).

1. **Executable only when SUPPORTED** — `EngineRegistry.resolveCapability` must project `SUPPORTED` for the exact phase-capability cell.
2. **SUPPORTED requires evidence** — only `QUALIFIED_WITH_ENVELOPE` **and** evidence `PASS` project SUPPORTED. Design `BASELINE`, stub adapters, and native library presence do **not**.
3. **Fail closed on UNKNOWN** — missing cell, non-envelope status, or non-PASS evidence ⇒ UNKNOWN / not selected (INV-018).
4. **Only llama-cpp may use real native** in this attach path. Peers remain stub/UNKNOWN until their own native/SDK + device evidence land.
5. **Native ≠ qualified** — loading `libomnillm_llama` does not flip cells to SUPPORTED.
6. **No envelope inheritance** — evidence never generalizes across backend / device / model / workload.
7. **Transports do not select engines** (ADR-011) — Orchestrator + Registry own routing.

## What attach deliberately does not do

- Mark any cell `QUALIFIED` / `SUPPORTED` without real device evidence packs
- Substitute `StubNativeBackend` for production llama-cpp when native is missing
- Load LiteRT / MLC / mllm / ORT native or vendor SDK in this process
- Write OmniLLM domain DB from engine adapters (ADR-010)

## Tests

- `:android:runtime-service` → `EnginePackAttachmentTest`
  - All catalog engines registered
  - Zero SUPPORTED projections after attach
  - Stub llama-cpp engine does not elevate capability
  - Only llama-cpp is native-eligible per policy
- `:engines:api` → `EngineRegistryTest` (projection pure rules)

## Wiring

```
RuntimeControlPlane.attach
  → WaveAWiring.wire(engineExecute = EngineExecuteBinding)
       Orchestrator ← DelegatingInferenceEngine (starts fail-closed)
       Playground/Server/Tools/Routing ports ← Orchestrator path
  → ensureStarted → READY|DEGRADED
  → ensureEnginePacksAttached
  → EnginePackAttachment.attachAfterReady
  → EngineRegistry + (optional) LlamaCppEngine(JniNativeBackend)
  → EngineExecuteBinding.applyAttachment
       DelegatingInferenceEngine.bind(LlamaCppInferenceEngineAdapter)
```

### Exploratory execute (honest CONDITIONAL)

| Condition | Capability projection | Execute |
|---|---|---|
| Native missing | UNKNOWN | Fail-closed clear error |
| Native attached, `runtime.exploratoryExecuteEnabled=false` (default) | UNKNOWN | Fail-closed (explicit opt-in required) |
| Native attached, flag true | **CONDITIONAL** (not SUPPORTED) | Plan→Reserve→Commit→Execute via Orchestrator using **explicit** `fixture:EXPERIMENTAL_FIXTURE` load markers |
| Real install without path/FD / fixture markers | UNKNOWN | Fail closed — **no silent fixture** |
| Cross engineBuildId / revision | UNKNOWN | Fail closed — no silent fallback |

Setting: `runtime.exploratoryExecuteEnabled` (LOCAL_ADMIN, default false) in
`specs/configuration-catalog.yaml` / `ConfigurationCatalog`.

Gradle: `:android:runtime-service` depends on `:engines:api` and every catalog Engine Pack module; `:android:native` supplies `libomnillm_llama` for the runtime process only.
