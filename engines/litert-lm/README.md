# Engine Pack: LiteRT-LM

| Field | Value |
|---|---|
| Module | `:engines:litert-lm` |
| Product doc | `ENGINE-LITERT` — `docs/80-engines/litert-lm.md` |
| Standard | `ENGINE-STANDARD` — `docs/80-engines/engine-integration-standard.md` |
| Design status | `BASELINE` |
| Upstream lock | `LOCKED` (v0.15.0, commit `2117fc43`, AAR digest in `UPSTREAM.lock`) |
| Qualification status | `UNQUALIFIED` (all cells — no device evidence yet) |
| Registry exposure | `UNKNOWN` (never `SUPPORTED` without evidence) |
| Integration status | `INTEGRATED` (Stage 2E: typed real SDK backend replaces stub/reflection bridge) |
| Upstream | https://github.com/google-ai-edge/LiteRT-LM |
| Official Android docs | https://developers.google.com/edge/litert-lm/android |

## Purpose

Software-complete adapter pack that maps OmniLLM `OmniEngine` / `LoadedModelPort`
operations onto the official LiteRT-LM Kotlin API **Engine / Conversation** surface.

Since Stage 2E this pack binds the **real** SDK — no reflection, no stub in the
production path:

- Pinned `com.google.ai.edge.litertlm:litertlm-jvm:0.15.0` (`compileOnly`) gives the
  module a **typed** compile-time dependency on the official API (same surface as the
  Android AAR; the JVM artifact is pure JVM — zero `android.*` references).
- `OfficialLitertLmSdkBridge` implements the engine SPI bridge with direct calls:
  `Engine(EngineConfig(...))` → `initialize()` → `createConversation()` →
  `sendMessageAsync(text, MessageCallback)` (real streaming) → `cancelProcess()`.
- The pack still does **not** claim runtime `SUPPORTED` for any backend × device ×
  model × workload cell.

## Integration shape (ENGINE-LITERT §2)

- Prefer the official documented SDK / AAR form (`com.google.ai.edge.litertlm`).
- Model artifacts: `.litertlm` bundles (optimized TFLite + tokenizer + metadata) —
  **GGUF is not supported upstream** (no loading or conversion path). Never advertise
  GGUF for this engine.
- Adapter owns Engine / Conversation objects, stream callbacks, and backend
  configuration as **opaque** tokens.
- **Never** pass SDK objects across AIDL / process boundaries.
- Adapter **must not** write OmniLLM DB / model store (ENGINE-STANDARD §3, ADR-010).

## Package layout

```
engines/litert-lm/
  README.md
  UPSTREAM.lock              # LOCKED (v0.15.0 pin + AAR/native/16KB evidence)
  capability-matrix.yaml     # design rows; all UNQUALIFIED / runtimeDefault UNKNOWN
  build.gradle.kts           # compileOnly litertlm-jvm:0.15.0 (+ testImplementation)
  src/main/kotlin/com/omnillm/engines/litertlm/
    LitertLmModule.kt        # factory + EngineRegistry registration
    LitertLmEngine.kt        # OmniEngine (plan/commit/query/bind)
    LitertLmLoadedModelPort.kt  # plan/commit/start/embed/close/unload + cancel
    lock/UpstreamLock.kt
    mapping/                 # ErrorMapper, EventNormalizer, ParameterValidator, PhaseCancellation
    resource/ResourceEnvelopeEstimator.kt
    sdk/
      SdkBackend.kt          # SPI (tokens, error codes, stream kinds)
      StubSdkBackend.kt      # host unit tests only (never silent production fallback)
      RealSdkBackend.kt      # production path; policy-gated exploratory execute
      LitertLmApiMapping.kt  # pure mapping onto real SDK types (testable on host)
      LitertLmSdkBridge.kt   # Absent (fail closed) + OfficialLitertLmSdkBridge (typed, real API)
      SdkBackendFactory.kt   # production vs host-test selection
```

## SDK backend selection

| Backend | When | Behavior |
|---|---|---|
| `StubSdkBackend` | Host unit tests via `SdkBackendFactory.forHostUnitTests()` | Dry-run plumbing; never elevates trust |
| `RealSdkBackend` + `OfficialLitertLmSdkBridge` | Production via `LitertLmModule.createProductionEngine()` | Typed real SDK calls; fail closed (`NOT_AVAILABLE`) when the SDK is absent; exploratory execute only when lock complete + policy flag |

**Hard rule:** production must **not** silently fall back from `RealSdkBackend` to
`StubSdkBackend` when the SDK is missing (INV-018). Missing AAR / natives ⇒
`NOT_AVAILABLE` / `CAPABILITY_UNKNOWN`, never success.

```kotlin
// Host tests
val eng = LitertLmModule.createEngine() // StubSdkBackend

// Runtime (still UNQUALIFIED; execute fail-closed unless policy + complete lock)
val prod = LitertLmModule.createProductionEngine(forceExploratory = false)
```

Registry attach (`EnginePackAttachment`) registers metadata + UNQUALIFIED cells only;
it does not load the SDK in the control plane.

## Pinned artifact (2026-08-09)

| Coordinate | Version | Purpose |
|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-jvm` | `0.15.0` | compileOnly type surface + host test classpath in this JVM module |
| `com.google.ai.edge.litertlm:litertlm-android` | `0.15.0` | **Android runtime packaging only** — must be added to `:android:runtime-service` (or companion), never `:android:app-ui` (INV-001) |

Evidence in `UPSTREAM.lock`: AAR sha256 `b398c474…`, per-ABI native lib digests
(arm64-v8a / x86_64), Apache-2.0 license digest, 16 KB page alignment PASS for both
ABIs (`tools/ci/check_elf_16kb_alignment.py`), AAR `minSdkVersion=24` (product
minSdk 28 — no conflict).

## Path broker (never raw client paths)

`RealSdkBackend` / official bridge refuse load without
`attributes["resolvedModelPath"]` from the **privileged path/FD broker** after
re-verify ticket. Client absolute paths must not cross AIDL.

## Exploratory execute (policy)

Even with the SDK present:

- Incomplete lock ⇒ load/generate stay `CAPABILITY_UNKNOWN`.
- Complete lock + `forceExploratory=true` may allow CONDITIONAL exploratory
  execute for packaged models when control-plane policy allows.
- **Capability matrix / qualification YAML remain UNQUALIFIED** until real
  device evidence packs land. SDK presence ≠ `SUPPORTED`.

## Official API surface used (v0.15.0)

```kotlin
import com.google.ai.edge.litertlm.*

val engine = Engine(
    EngineConfig(
        modelPath = resolvedPath,          // from privileged path broker
        backend = Backend.CPU(),           // GPU() / NPU(nativeLibraryDir=…)
        cacheDir = cacheDir,               // optional writable dir
    ),
)
engine.initialize()                        // blocking; run off the main thread
engine.createConversation(ConversationConfig()).use { conversation ->
    conversation.sendMessageAsync(prompt, object : MessageCallback {
        override fun onMessage(message: Message) { /* cumulative text → delta */ }
        override fun onDone() { /* COMPLETED */ }
        override fun onError(t: Throwable) { /* CancellationException / LiteRtLmJniException */ }
    })
    conversation.cancelProcess()           // best-effort cancel
    conversation.tokenCount                // KV-cache tokens (approximate usage)
}
engine.close()
```

Map:

| SDK | OmniLLM |
|---|---|
| Engine init / close | LOAD commit / UNLOAD |
| createConversation / close | CREATE_SESSION / CLOSE |
| sendMessageAsync(callback) / sendMessage | START / GENERATE |
| cancelProcess | cooperative cancel (best-effort; measured mode UNKNOWN) |
| tokenCount (KV cache) | USAGE (kv-cache-approximate) |
| (no qualified embed API) | EMBED → CAPABILITY_UNKNOWN |

## Upstream lock

See [UPSTREAM.lock](./UPSTREAM.lock). Complete lock (`LOCKED`) satisfies the
supply-chain pin (ENGINE-LITERT §1); `EngineRegistration.upstreamLocked == true`.
Registry cells stay `UNQUALIFIED` / project `UNKNOWN` until device evidence.

## Operation mapping (ENGINE-LITERT §4)

| Omni operation | Adapter behavior |
|---|---|
| PROBE | Pure plan; RealSdkBackend probes SDK presence (not device SUPPORTED) |
| LOAD | Pure plan + commit ledger; Real fails closed without lock/policy/path broker |
| CREATE / SELECT SESSION | Opaque conversation id; no cross-process reuse |
| PLAN / COMMIT inference | Plan pure (ADR-002); commit one-shot / queryable; maxTokens captured for start |
| START / GENERATE | Real streaming via MessageCallback → catalog events; `requestCancel` cooperative + `cancelProcess()`; unproven cancel ⇒ killable worker |
| EMBED | Fail closed (`CAPABILITY_UNKNOWN`) until cell evidence |
| CLOSE / UNLOAD | Best-effort; worker death invalidates all conversations |

## Phase cancellation (ENGINE-LITERT §6)

Measured modes default **UNKNOWN**. `cancelProcess()` stops future output; upstream
state-rollback is a pending feature (b/450903294) — stopping output ≠ native execution
stopped. UNKNOWN measured mode ⇒ full LoadedModel lifecycle on a killable worker.

## Resource envelope (ENGINE-LITERT §7)

`ResourceEnvelopeEstimator` covers SDK fixed overhead, model bytes, compiled
cache, KV/session, workspace, CPU/GPU/NPU, threads, FD, temporary disk.
Vendor self-report must not lower a qualified envelope. Estimates are advisory
for Plan; Governor reservation is authoritative.

## Qualification cells

`capability-matrix.yaml` seeds placeholder cells as:

- `qualificationStatus: UNQUALIFIED`
- `evidenceStatus: NOT_EXECUTED`
- `cancellationMode: UNKNOWN`
- `runtimeDefault: UNKNOWN`

Registry rule: only `QUALIFIED_WITH_ENVELOPE` + evidence `PASS` projects
`SUPPORTED`. Design `BASELINE` ≠ runtime supported.

Seed via:

```kotlin
LitertLmModule.registerWith(registry)
LitertLmModule.seedUnqualifiedPlaceholders(registry, deviceFingerprint)
```

`EnginePackAttachment` does this for the runtime process after READY/DEGRADED.

## Known limitations (ENGINE-LITERT §10)

- **GGUF is not supported upstream** — model formats are `.litertlm` / `.tflite`
  (legacy `.task`). Do not advertise GGUF.
- Prefix truncate / fork, cross-session reuse, embedding, multimodal, and
  structured output require SDK version + model cell evidence.
- Vendor / NPU memory and cancel observability may be insufficient → conservative
  envelopes and killable workers.
- `getTokenCount()` is the KV-cache total — usage events are approximate, not exact
  prompt/completion splits.
- SDK artifact / model schema may not be backward compatible → isolate via
  `EngineBuildId`.
- Accelerated paths that cannot run under a different UID must **not** carry
  untrusted artifacts (ADR-007 / INV-009).

## Hard rules

1. UI process never loads this engine (INV-001).
2. Plan → Reserve → Commit → Execute; Plan has no domain mutation (ADR-002).
3. Single writer: adapter never writes DB / model store (ADR-010).
4. Client-generated requestId / idempotencyKey; claim-or-return; query on reply loss
   (ADR-004 / 005).
5. Unknown capability / cancel / envelope ⇒ fail closed (INV-018).
6. Do not invent types / enums / states / errors absent from catalogs.
7. Do not mark cells QUALIFIED/SUPPORTED without device evidence.

## Status

| Dimension | Value |
|---|---|
| Design | BASELINE (product docs complete) |
| Upstream lock | LOCKED — v0.15.0 pin + AAR/native/16KB/license evidence (2026-08-09) |
| Implementation | Typed real SDK backend (`OfficialLitertLmSdkBridge`), compile-verified against litertlm 0.15.0 |
| Qualification | UNQUALIFIED for all cells (no device evidence) |
| Runtime claim | UNKNOWN only |

## Residual human-only items

1. Package `litertlm-android:0.15.0` into `:android:runtime-service` (or companion)
   — never app-ui (INV-001); GPU needs optional `libOpenCL.so` / `libvndksupport.so`
   manifest entries; NPU needs `npuNativeLibraryDir`.
2. Wire privileged path/FD broker → `resolvedModelPath` attributes (and inject
   `promptText` after privileged re-verify for generate).
3. Stage 5 instrumented tests on real devices: load a `.litertlm` catalog model,
   measure phase cancellation + resource envelopes, publish evidence cells
   (never inherit CPU evidence onto GPU/NPU).
4. Physical device / OEM matrix and Play submission (out of this pack’s scope).
5. Stage 4b doc gap: `docs/80-engines/litert-lm.md` (BASELINE) should note the
   typed `OfficialLitertLmSdkBridge` integration shape and the GGUF unsupported fact.
