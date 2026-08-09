# Engine Pack: MLC-LLM (`:engines:mlc-llm`)

| Field | Value |
|---|---|
| **engineId** | `MLC-LLM` |
| **Design authority** | `ENGINE-MLC` (`docs/80-engines/mlc-llm.md`) |
| **Integration standard** | `ENGINE-STANDARD` (`docs/80-engines/engine-integration-standard.md`) |
| **designStatus** | `BASELINE` (design-complete) |
| **upstreamLockStatus** | `NOT_LOCKED` (upstream pin 2f78caa4 + TVM pin captured; build digests pending) |
| **qualificationStatus** | `UNQUALIFIED` |
| **registryExposure** | `UNKNOWN` |
| **runtimeCapabilityDefault** | `UNKNOWN` |
| **integrationStatus** | `INTEGRATED` (real mlc4j runtime binding; `PENDING_QUALIFICATION` — no device inference evidence yet) |

> **Hard rule:** Design completion is **not** runtime support.  
> Registry may project `SUPPORTED` only for cells with  
> `qualificationStatus=QUALIFIED_WITH_ENVELOPE` **and** `evidenceStatus=PASS`.  
> This pack never claims `SUPPORTED` without that evidence.

## Purpose

Software-complete **compiler + generated model library + runtime** adapter
that maps OmniLLM `OmniEngine` / `LoadedModelPort` onto MLC-LLM’s offline
compile pipeline and on-device `MLCEngine` / chat stream surface.

Stage 2E (2026-08-09) replaced the exploratory stub with a **real runtime
integration**: `MlcEngineRuntimeBackend` + `MlcRuntimeBridge` bind the official
generated Android runtime (`ai.mlc.mlcllm.MLCEngine`, produced per-app by
`mlc_llm package`). Host JVM tests exercise the full binding path against a
faithful test double of the pinned API.

Production attach is `MlcLlmModule.createEngineWithRuntimeOrNull()` — it
**fail-closes** (returns null) when the mlc4j runtime is not on the classpath.
`StubRuntimeBackend` remains **unit-test only** and is never substituted
silently. Model load additionally fail-closes until `UPSTREAM.lock` is complete
(pinned artifact digests) — no fake inference on unpinned artifacts.

## Integration shape (ENGINE-MLC §2)

```text
                  ┌─────────────────────┐
  offline Job ──► │ MLC compile (TVM)   │  → generated .so / module + weights package
                  └─────────────────────┘
                             │ digest in ArtifactPackage / ModelRevision (ADR-008)
                             ▼
                  ┌─────────────────────┐
  runtime ──────► │ RuntimeBackend      │  load verified generated lib + MLCEngine
                  │ (opaque tokens)     │  stream callbacks → catalog EngineEvent
                  └─────────────────────┘
```

- Model package includes compiled executable code, metadata, tokenizer, and weights.
- Adapter owns opaque `NativeModelToken` / `NativeSessionToken` only — never raw pointers on AIDL.
- Generated libraries are **not** ordinary data blobs; untrusted code must not enter a trusted process (ADR-007 companion UID).
- Adapter **must not** write OmniLLM DB / model store (ADR-010).

## Module layout

```text
engines/mlc-llm/
  README.md                 # this file + human pin / native integration guide
  UPSTREAM.lock             # upstream pin (2f78caa4 + TVM 837cb9de) captured; build digests pending
  capability-matrix.yaml    # design matrix + UNQUALIFIED cell placeholders
  build.gradle.kts
  src/main/kotlin/com/omnillm/engines/mlcllm/
    MlcLlmModule.kt         # factory + EngineRegistry registration (+ createEngineWithRuntimeOrNull)
    MlcLlmEngine.kt         # OmniEngine adapter (Plan pure; execute via RuntimeBackend)
    MlcLlmLoadedModelPort.kt
    QualificationCells.kt   # UNQUALIFIED cell seed helpers
    lock/UpstreamLock.kt    # parse + isComplete() gate
    mapping/                # errors, phase cancel, parameters, event normalizer
    runtime/                # RuntimeBackend SPI + real MlcEngineRuntimeBackend + bridge
    resource/               # ResourceEnvelope estimator (advisory Plan only)
```

## Operation mapping (ENGINE-MLC §4)

| OmniLLM phase | MLC mapping (design) | Scaffold runtime |
|---|---|---|
| PROBE | package / target / runtime compatibility | Plan pure; execute → real backend: runtime presence + honest backend availability (opencl shipped, cpu/vulkan not) |
| LOAD | runtime + generated module load | Plan pure; commit → real backend: model dir + mlc-chat-config.json verified, `reload(modelPath, modelLib)`; **fail-closed until complete lock** |
| PLAN_INFERENCE | no KV mutation (ADR-002) | Pure envelope + digests; sampling params (maxTokens/temp/topP/promptUtf8/stop) captured |
| COMMIT_INFERENCE | chat/session create, prompt prep | One-shot commit journal; session = adapter-side message journal (MLC chat API is stateless per request) |
| START / GENERATE | stream generation callbacks | `chat.completions.create` stream → delta/usage/stop events; cooperative cancel between deltas |
| EMBED | pinned API only | Always `CAPABILITY_UNKNOWN` until cell PASS |
| CLOSE / UNLOAD | session / module release | Best-effort; `unload()` on last model release |

## Phase cancellation (ENGINE-MLC §5)

| Phase | Expected (design) | Measured (scaffold) |
|---|---|---|
| PROBE | COOPERATIVE | UNKNOWN |
| LOAD | WORKER_KILL_ONLY | UNKNOWN |
| CREATE_SESSION / PLAN / COMMIT | COOPERATIVE | UNKNOWN |
| START | INTERRUPTIBLE | UNKNOWN |
| GENERATE | COOPERATIVE | UNKNOWN — real backend polls between stream deltas (mobile API has no abort) |
| CLOSE / UNLOAD | WORKER_KILL_ONLY | UNKNOWN |

UNKNOWN cancellation ⇒ full LoadedModel lifecycle on killable worker only.
GPU command cancel may be unbounded ⇒ trust-dependent companion / worker.

## Resource envelope (ENGINE-MLC §6)

Dimensions: generated code/module, CPU anon/file, weights, KV, runtime workspace,
GPU dedicated/shared/driver, kernel compile peak, temporary disk, threads.

- CPU RSS **must not** represent GPU allocation.
- Unknown driver allocation uses conservative upper bounds (zeros rather than invented peaks for GPU).
- Estimates are **advisory for Plan**; Governor reservation is authoritative.

## Upstream lock — human pin checklist (ENGINE-MLC §1)

See [UPSTREAM.lock](./UPSTREAM.lock). Empty digests ⇒ `NOT_LOCKED`.
Incomplete lock ⇒ Registry exposure stays `UNKNOWN`; qualification ineligible.

| Field family | Fill before qualification |
|---|---|
| MLC pin | `upstream.repository`, `tag` and/or `commit`, `sourceDigest` |
| TVM pin | `upstream.tvmCommit`, `tvmSourceDigest` |
| Patch | `upstream.patchDigest` present (empty string = no patches) |
| Compiler | `compilerConfigDigest`, `target`, `targetDigest`, `modelConfigDigest` |
| Generated | `generatedSourceDigest`, `generatedLibraryDigest` |
| Runtime | `artifact.runtimeArtifactDigest`, `artifact.artifactDigest` |
| Toolchain | `ndkVersion`, `cmakeVersion`, `toolchainDigest`, `hostOs`/`hostArch` |
| ABI / page | `build.abis` non-empty; `pageSizeEvidence` (16 KB) |
| Identity | `artifact.engineBuildId`, `upstream.observedAt` (ISO-8601 UTC) |
| License | `licenseDigest`, notices, dependency / generated artifact license digests |

Updating **any** lock field ⇒ new `EngineBuildId`; old evidence does not auto-carry.

### Example pin (do not invent digests in-tree)

```yaml
lockState: LOCKED
upstream:
  repository: "https://github.com/mlc-ai/mlc-llm"
  tag: "vX.Y.Z"
  commit: "<full-sha>"
  sourceDigest: "<sha256-of-source-tree>"
  tvmCommit: "<full-tvm-sha>"
  tvmSourceDigest: "<sha256>"
  patchDigest: ""          # recorded empty = no OmniLLM patches
  observedAt: "2026-..T..Z"
compiler:
  compilerConfigDigest: "<sha256>"
  target: "opencl-android" # or llvm / vulkan / ...
  targetDigest: "<sha256>"
  modelConfigDigest: "<sha256>"
  generatedLibraryDigest: "<sha256>"
toolchain:
  ndkVersion: "28.2.13676358"
  toolchainDigest: "<sha256>"
build:
  abis: ["arm64-v8a"]
  pageSizeEvidence: "<evidence-id-or-digest>"
artifact:
  engineBuildId: "mlc-llm-<opaque>"
  artifactDigest: "<sha256>"
  runtimeArtifactDigest: "<sha256>"
license:
  licenseDigest: "<sha256>"
```

## Registry registration (EnginePackAttachment)

Production control plane (`EnginePackAttachment.attachAfterReady`) registers
**metadata + UNQUALIFIED cells only** — it does **not** load MLC natives or attach
an exploratory execute engine.

```kotlin
val registry = EngineRegistry()
// Metadata only; cells seeded with device fingerprint by EnginePackAttachment:
val reg = MlcLlmModule.registerWith(registry, seedPlaceholderCells = false)
MlcLlmModule.seedUnqualifiedPlaceholders(registry, deviceFingerprint, reg.engineBuildId)
// reg.designStatus == "BASELINE"
// reg.upstreamLocked == false (lock not complete)
// cells: UNQUALIFIED + evidence NOT_EXECUTED → project UNKNOWN
// never SUPPORTED without QUALIFIED_WITH_ENVELOPE + PASS
```

Unit / architecture wiring (not production attach):

```kotlin
val engine = MlcLlmModule.createEngine(
    backend = StubRuntimeBackend(exploratoryDryRun = false), // host tests only
)
// Exploratory plumbing tests only:
val dry = MlcLlmModule.createEngine(
    backend = StubRuntimeBackend(exploratoryDryRun = true),  // CPU dry-run only
)
```

Production attach (real runtime — fails closed when mlc4j is absent):

```kotlin
val engine = MlcLlmModule.createEngineWithRuntimeOrNull()  // null ⇒ no runtime
```

## Runtime integration (Stage 2E) — what is real vs pending

**Real (in-tree, compiled + host-tested):**

- `runtime/MlcRuntimeBridge.kt` — reflective binding to the official
  `ai.mlc.mlcllm` Kotlin API (pinned commit 2f78caa4; strict load-time API
  verification; fail-closed). Handles suspend `chat.completions.create` via a
  continuation bridge and drains the streaming `ReceiveChannel`.
- `runtime/MlcEngineRuntimeBackend.kt` — full `RuntimeBackend` implementation:
  honest probe (opencl shipped by stock mlc4j; cpu/vulkan not), model-bundle
  validation (`mlc-chat-config.json` + weights), `reload(modelPath, modelLib)`,
  session journal with multi-turn replay, streaming deltas → `TOKEN_DELTA`
  (SHA-256 of real delta text) + `USAGE` + `STOP`, cooperative cancellation,
  error mapping (`MODEL_OPEN_FAILED`, `MODULE_LOAD_FAILED`, `CANCELLED`, …).
- `MlcLlmModule.createEngineWithRuntimeOrNull()` production factory.
- Sampling params (maxTokens / temperature / topP / promptUtf8 / stopSequences)
  flow from request attributes through the port to the real backend.
- Host tests (19) bind the bridge against a faithful test double of the pinned
  API under `src/test/kotlin/ai/mlc/mlcllm/` (the real mlc4j artifact is
  Android-only and generated per-app, so it cannot be a JVM test dependency).

**Pending (Stage 5/6 — human/device work, honestly gated):**

1. `UPSTREAM.lock` is `NOT_LOCKED`: upstream + TVM pins and source/license
   digests are captured, but compiler/generated/toolchain/artifact digests
   require an actual `mlc_llm package` build. Until then the real backend
   **refuses model load** (`MODULE_LOAD_FAILED`).
2. The generated mlc4j module (`libtvm4j_runtime_packed.so` + `tvm4j_core.jar`
   + Kotlin API) must be produced by upstream tooling and included in the app —
   no Maven/GitHub-release `.aar` exists (verified 2026-08-09).
3. A compiled model bundle is required (e.g. HF `mlc-ai/TinyLlama-1.1B-Chat-v1.0-q4f16_1-MLC`
   or `mlc-ai/Phi-3-mini-4k-instruct-q4f16_1-MLC`, ~0.7–2.3 GB).
4. 16 KB page-size alignment of `libtvm4j_runtime_packed.so` must be measured
   (Play requirement; upstream publishes no claim).
5. Device/GPU inference + cancellation + resource-peak evidence → qualification
   cells (`UNQUALIFIED` today).

## Native / SDK integration guide (human pin steps)

The real MLC Android runtime is **not** vendored in this monorepo and not
published as an artifact; it is generated per-app. To go from `INTEGRATED`
(now) to `PENDING_QUALIFICATION` → evidence:

1. **Build the runtime**: clone mlc-llm at commit `2f78caa4` (see
   `UPSTREAM.lock`), set up `ANDROID_NDK` / `TVM_NDK_CC` / `JAVA_HOME`, run
   `mlc_llm package` (or `android/mlc4j/prepare_libs.py`) to produce
   `dist/lib/mlc4j` (`libtvm4j_runtime_packed.so` + `tvm4j_core.jar` + Kotlin API).
2. **Provision the model bundle**: compiled model directory (`mlc-chat-config.json`
   + weights) from a HF `mlc-ai/*-MLC` repo (e.g. TinyLlama/Phi-3 q4f16_1),
   and a compiled model library per backend.
3. **Pin the lock**: capture `runtimeArtifactDigest` (the built
   `libtvm4j_runtime_packed.so`), `artifactDigest`, `toolchainDigest`,
   per-model `generatedLibraryDigest`, `pageSizeEvidence` (16 KB ELF scan) and
   set `engineBuildId` + `lockState: LOCKED`. New `EngineBuildId` ⇒ re-pin.
4. **Attach in the app**: include the generated mlc4j module in the APK
   classpath (app-level dependency), then use
   `MlcLlmModule.createEngineWithRuntimeOrNull()` — the bridge verifies the
   pinned API surface and fail-closes on mismatch.
5. **Qualify** per backend × device × driver × model × workload cell with
   measured cancellation + resource peaks; put `QUALIFIED_WITH_ENVELOPE` + `PASS`
   only with real evidence packs (device matrix is out of software scope).
6. **EngineSelectionPolicy**: peer engines stay UNKNOWN until policy + evidence
   allow real native attach.

## Known limitations / unsupported-by-default (ENGINE-MLC §10)

1. Any backend without pinned compiler/runtime/target is **`UNKNOWN`**.
2. Generated code raises isolation requirements above pure-weight formats.
3. GPU memory / cancellation / driver recovery are OEM-dependent — no global guarantee.
4. Prefix, embedding, multimodal, tool/structured require per model/API cell validation.
5. Old compiled artifacts are not assumed compatible with a new runtime (ENGINE-MLC §9).
6. Dry-load / exploratory stub **never** elevates model trust (INV-008).
7. No silent cross-revision or cross-backend evidence inheritance.
8. `topK` sampling is fail-closed `UNSUPPORTED_PARAMETER` (mobile chat API has no `top_k`).
9. Stock generated mlc4j ships OpenCL only — CPU/Vulkan execution is not claimed.

## Hard adapter rules (ENGINE-STANDARD §3)

Adapters must **not**:

- write OmniLLM DB / model store (ADR-010)
- return native pointers across process
- silently ignore unsupported parameters
- elevate trust via dry-load
- claim global support from a single device cell
- mutate KV / load large resources during Plan (ADR-002)
- claim `SUPPORTED` without QUALIFIED_WITH_ENVELOPE + PASS
- substitute the unit-test stub for the real backend silently (INV-018)

## Tests

```bash
./gradlew :engines:mlc-llm:test
```

Coverage includes:

- Default stub → `CAPABILITY_UNKNOWN` on probe/load/start/embed (host tests)
- Exploratory CPU Plan→Commit→Start→Close→Unload plumbing
- Accelerator backends remain UNKNOWN even in exploratory mode
- Mapping (errors, sanitize, events, phase cancel, params)
- Resource envelopes (CPU/GPU separation, conservative placeholders)
- Upstream lock completeness + parse
- Registry seed projects UNKNOWN, never SUPPORTED
- **Real backend (19 tests)**: runtime binding against the pinned API test
  double — honest probe, digest-gated load, model-bundle validation, streaming
  deltas with real SHA-256 payload digests, usage/stop events, cooperative
  cancel, multi-turn journal, path-leak-free errors, lifecycle release
- Instrumented on-device inference (real mlc4j + compiled model) is a Stage-5
  concern — not executable on the host JVM

## Remaining human-only work

- [x] Replace stub with real runtime binding (Stage 2E)
- [ ] Build `mlc4j` from the pinned commit (NDK + Rust + TVM toolchain) — no
      prebuilt `.aar` exists upstream
- [ ] Provision a compiled model bundle (HF `mlc-ai/*-MLC`, e.g. TinyLlama/Phi-3 q4f16_1)
- [ ] Complete `UPSTREAM.lock` (runtime/generated/toolchain digests + 16 KB evidence)
- [ ] 16 KB page-size packaging evidence (ELF scan of `libtvm4j_runtime_packed.so`)
- [ ] Per-backend/device/driver/model/workload qualification cells with PASS evidence
- [ ] Measured phase cancellation + resource peaks
- [ ] Companion GPU path for untrusted accelerated inference (ADR-007)
- [ ] Physical device / OEM matrix (out of software scaffold scope)
