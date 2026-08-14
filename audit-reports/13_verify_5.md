# Adversarial verification — Claim #5

| Field | Value |
|---|---|
| **Claim** | Playground can generate when exploratory enabled and model installed |
| **real** | **false** |
| **Auditor** | Independent re-read of NEW docs package + monorepo sources (not prior audit prose alone) |
| **Date** | 2026-08-12 |
| **Out path** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\13_verify_5.md` |

```yaml
claim_id: 5
claim: "Playground can generate when exploratory enabled and model installed"
real: false
```

---

## Verdict

**real: false** — The product has a **gated L1/L2 software path** that *attempts* exploratory chat when engine-bound + `runtime.exploratoryExecuteEnabled` (or dev-ship mode) + a real installation exists, but there is **no concrete evidence** that Playground **returns usable generated assistant text** under those conditions.

Fail-closed default: without proof that generation content surfaces to Playground (UI strip / conversation / `assistantText`), the claim that Playground “can generate” is not PASS.

---

## reason

Reading “can generate” as a **product journey outcome** (user/Playground obtains model output when exploratory is on and a model is installed):

1. **Gates exist and fail closed** — exploratory off ⇒ startChat rejected; no installation ⇒ startChat rejected; capability UNKNOWN blocks chat tab.
2. **Submit/pump may complete** a request lifecycle (stub/native generate invoked with digests only).
3. **Production control-plane Playground port never populates `assistantText`.** Stream events carry **payload digests only** (no token plaintext). HTTP sync chat itself documents fail-closed when aggregated text is missing.
4. **No automated test** calls real `ControlPlaneFeaturePorts.playgroundInference().startChat` with exploratory + installed model and asserts non-blank assistant text.
5. **Device Appium journey** observed empty Playground gate and **no** generate output (historical residual on flag/import; still no positive device evidence of generate).

Therefore: software **entry gates** for the claim’s preconditions are real; **end-to-end generate (text) through Playground is not evidenced** → claim is **false**.

---

## evidence

### E1 — Docs / design (Playground is designed to chat; exploratory is implementation policy)

| Path | Fact |
|---|---|
| `…\OmniLLM_Product_Documents\docs\70-features\local-playground.md` | FEAT-PLAYGROUND: Chat first request with Session policy; acceptance “首次 Chat：零 Session 可完成 plan/reserve/commit/start” |
| `…\OmniLLM_Product_Documents\docs\00-product\capability-availability-and-build-eligibility.md` | Only `SUPPORTED` / satisfied `CONDITIONAL` may execute; `UNKNOWN` must not be pretended supported |
| Monorepo `specs/configuration-catalog.yaml` L120–129 | `runtime.exploratoryExecuteEnabled` default **false**; opt-in CONDITIONAL exploratory TEXT_GENERATION when native attached / cells UNQUALIFIED — never elevates YAML to SUPPORTED |
| Docs package `specs/` grep for `exploratoryExecute` | **No matches** (repo catalog has the key; docs-package mirror may lag — CHANGELOG FTR-05 notes this) |

### E2 — L1 module + UI send path (exists)

| Path | Quote / symbol |
|---|---|
| `features/playground/…/PlaygroundViewModel.kt` | `sendChat` → `api.startChat`; blocks when `tabCapability?.operable == false` |
| `features/playground/…/PlaygroundService.kt` | `startChat` → `gateCapabilities` then `ports.inference.startChat`; `appendConversation(..., strip.assistantText)` |
| `features/playground/…/PlaygroundProjections.kt` | Tab operable iff every required cap is `SUPPORTED` **or** `CONDITIONAL` |
| `android/app-ui/…/PlaygroundScreen.kt` | Send enabled when prompt non-blank, model selected, operable; calls `viewModel.sendChat(ChatRequestSpec(...))` |
| `android/app-ui/…/strings.xml` | `playground_empty_no_model` = “No usable model for inference” when catalog empty |

### E3 — L2 wiring + precondition gates (exploratory + installed model)

| Path | Quote / fact |
|---|---|
| `android/runtime-service/…/WaveAWiring.kt` ~L288–307 | `PlaygroundModule.createApi` with `ControlPlaneFeaturePorts.playgroundInference(...)`, capabilities from binding, models from `modelManager.listInstallations()` |
| `ControlPlaneFeaturePorts.kt` `OrchestratorPlaygroundInferencePort.startChat` L347–386 | Fail closed if engine unbound; if exploratory **off** and not dev-ship: `"experimental generate disabled; enable runtime.exploratoryExecuteEnabled"`; if no install: `"no installed model for requested revision (fail closed, ARC-06)"` |
| same L395–447 | Builds `OrchestrationRequest` (`operationKind = "CHAT"`, `TEXT_GENERATION`), `orchestrator.submit` + `pumpOnce`; returns `InferenceHandle` with state / degraded reasons **without `assistantText`** |
| `EngineExecuteBinding.kt` L187–190, L276–284 | Without exploratory (compliance): capability `UNKNOWN`; with exploratory: `CONDITIONAL`; setting key `runtime.exploratoryExecuteEnabled` |
| `SettingsScreen.kt` L149–186 | Switch for `runtime.exploratoryExecuteEnabled` (current tree has toggle + honest CONDITIONAL copy) |

### E4 — Engine “generate” runs, but digest-only (not product text)

| Path | Quote / fact |
|---|---|
| `engines/llama-cpp/…/LlamaCppLoadedModelPort.kt` L273–285 | `NativeGenerateRequest(promptDigestHex = prepared.canonicalInputDigest.hex, maxTokens = 16)`; `engine.native.generate(...)` |
| `engines/api/…/EngineEvents.kt` L37–49 | `EngineEvent` payload is digest/opaque; “no prompt/token plaintext by default” |
| `LlamaCppInferenceEngineAdapter.kt` L233–239 | Maps engine events to orchestrator `StreamEvent(seq, kind, payloadDigest)` only — **no text field** |
| `runtime/orchestrator/…/OrchestratorPorts.kt` `StreamEvent` | `kind` + `payloadDigest` only |
| Stub path `StubNativeBackend.kt` L147–152 | Emits `TOKEN_DELTA` with `payloadDigestHex = STUB_DELTA_DIGEST`, attributes index only — **no token string** |

### E5 — Feature-layer tests (fakes only for text)

| Path | Fact |
|---|---|
| `features/playground/…/FakePlaygroundPorts.kt` | Fake returns `assistantText = "hello from fake"` |
| `PlaygroundCapabilityNegotiationTest` | CONDITIONAL remains operable; startChat OK with **FakeInferencePort** |
| Grep `port.startChat` under `*Test*.kt` for real `ControlPlaneFeaturePorts` | **No production-port startChat success+text assertion** (cancel ladder constructs port but exercises cancel, not chat text) |

---

## counter_evidence

These **falsify** or **block** treating the claim as true:

### C1 — Production Playground port never returns assistant text

`OrchestratorPlaygroundInferencePort.startChat` builds:

```kotlin
InferenceHandle(
    requestId = ...,
    operationKind = "CHAT",
    state = state,
    actualModelRevisionId = ...,
    engineBuildId = ...,
    backend = "cpu",
    error = err,
    degraded = true,
    degradedReasons = listOf("CONDITIONAL exploratory execute", "engine cells UNQUALIFIED"),
)
// assistantText defaults to null — never set
```

Path: `android/runtime-service/src/main/kotlin/com/omnillm/android/runtimeservice/featurehost/ControlPlaneFeaturePorts.kt` (~L426–445).

Repo-wide `assistantText =` in main sources: fakes, tools structured adapter, projections/parsers — **not** the chat control-plane port.

### C2 — HTTP path admits control-plane lacks aggregated token text

`ControlPlaneHttpHandler.kt` (~L376–385):

> `"sync chat executed without aggregated token text; engine token deltas are not exposed on the control-plane API"`

Same port is used for Playground and HTTP chat. SSE polls `port.query()` which also **does not** set `assistantText` (query path L542–552). SSE regression test **injects** assistantText via a **fake** port (`ControlPlaneSseStreamRegressionTest.kt`), not the real orchestrator port.

### C3 — User prompt never reaches native as plaintext

Generate request uses **only** `promptDigestHex` (hash of client digest), not `spec.messages` content. Even if native decode produced tokens, the adapter contract drops plaintext before Playground.

### C4 — No integration proof of claim preconditions + output

- No test: exploratory=true + real `listInstallations` non-empty + `playgroundInference.startChat` → non-blank `assistantText` / conversation.
- `ControlPlaneCancelLadderTest` reaches `COMPLETED` via **direct orchestrator submit** with synthetic candidates, **empty** `ModelManagerModule.createInMemoryControlPlane()` — does **not** prove `startChat` under “model installed”.

### C5 — Device product journey negative evidence

`APPIUM_E2E_REPORT.md` steps E–F: Playground empty gate “No usable model for inference”; **no Send**; **no model output**. (Note: report’s Settings “no Switch” may be stale vs current `SettingsScreen` Switch — still **no positive device generate** exists in-repo.)

### C6 — Marketing / backlog claims vs code

`BUILD_STATUS.md` / `PRODUCT_READINESS_CHECKLIST.md` claim “SSE chat live” / “aggregated chat text real” — **not corroborated** by production `assistantText` wiring in `ControlPlaneFeaturePorts` / digest-only stream. Fail-closed audit treats narrative as non-evidence.

---

## residual

| ID | Residual | Impact on claim |
|---|---|---|
| R1 | Wire token plaintext (or bounded delta attributes) from llama-cpp native → adapter → `InferenceHandle.assistantText` / stream projection | **Blocks** product “generate” until fixed |
| R2 | Integration test: install READY revision + exploratory on + bound engine → `startChat` asserts non-blank text + COMPLETED | Needed for L3 software-complete |
| R3 | Device matrix: exploratory on, GGUF installed, Playground send → tokens on screen | Not present; do not invent PASS |
| R4 | Dev-ship mode (`allowExecuteWithoutQualification`) bypasses exploratory **flag** for execute gate but still lacks text plumbing | May widen execute admission without fixing generate content |
| R5 | Model install product path (SAF/import) historically stubbed in Appium; without READY install Playground stays EMPTY | Preconditions of claim may be hard to reach on device |
| R6 | Docs package configuration catalog missing `runtime.exploratoryExecuteEnabled` vs monorepo | Spec authority skew; does not create generate text |

---

## Level summary (L1 / L2 / L3)

| Level | Status | Notes |
|---|---|---|
| **L1** module exists | **Present** | `:features:playground` service/VM/ports/UI |
| **L2** wired to control plane | **Partial** | Wave-A wires inference/catalog/capabilities; gates for exploratory + install are real; **text path missing** |
| **L3** product journey software-complete | **Not evidenced** | No proven generate output under claim conditions |

---

## Search log (empty positive text path)

| Search | Result |
|---|---|
| `assistantText =` in `*.kt` main production chat path | Only tools structured / projections / fakes / admin JSON parse — **not** `OrchestratorPlaygroundInferencePort` |
| `port.startChat` in runtime-service tests | Only cancel-ladder constructs port; **no** startChat text assertion |
| `payloadText` / `tokenText` / aggregate helpers under `featurehost/` | **No matches** |
| Device evidence of Playground tokens | Appium: **No** |

---

## Final

```yaml
real: false
reason: >
  Exploratory + installed-model gates and orchestrator chat submit exist (L2 partial),
  but production Playground/control-plane path never surfaces generated assistant text
  (digest-only streams; assistantText unset; HTTP documents the gap; no E2E positive proof).
```
