# Engine Pack: MLC-LLM (`:engines:mlc-llm`)

| Field | Value |
|---|---|
| **engineId** | `MLC-LLM` |
| **Design authority** | `ENGINE-MLC` (`docs/80-engines/mlc-llm.md`) |
| **Integration standard** | `ENGINE-STANDARD` (`docs/80-engines/engine-integration-standard.md`) |
| **designStatus** | `BASELINE` (design-complete) |
| **upstreamLockStatus** | `NOT_LOCKED` (template placeholders only) |
| **qualificationStatus** | `UNQUALIFIED` |
| **registryExposure** | `UNKNOWN` |
| **runtimeCapabilityDefault** | `UNKNOWN` |

> **Hard rule:** Design completion is **not** runtime support.  
> Registry may project `SUPPORTED` only for cells with  
> `qualificationStatus=QUALIFIED_WITH_ENVELOPE` **and** `evidenceStatus=PASS`.  
> This pack never claims `SUPPORTED` without that evidence.

## Purpose

Software-complete **compiler + generated model library + runtime** adapter scaffold
that maps OmniLLM `OmniEngine` / `LoadedModelPort` onto MLC-LLM’s offline compile
pipeline and on-device `MLCEngine` / chat stream surface.

Default execute path is **explicit `CAPABILITY_UNKNOWN`** via
`StubRuntimeBackend(exploratoryDryRun = false)`. Exploratory dry-run is test-only
and **never** elevates Registry cells or model trust (INV-008).

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
  UPSTREAM.lock             # lock template (NOT_LOCKED) — complete fields for human pin
  capability-matrix.yaml    # design matrix + UNQUALIFIED cell placeholders
  build.gradle.kts
  src/main/kotlin/com/omnillm/engines/mlcllm/
    MlcLlmModule.kt         # factory + EngineRegistry registration
    MlcLlmEngine.kt         # OmniEngine adapter (Plan pure; execute via RuntimeBackend)
    MlcLlmLoadedModelPort.kt
    QualificationCells.kt   # UNQUALIFIED cell seed helpers
    lock/UpstreamLock.kt    # parse + isComplete() gate
    mapping/                # errors, phase cancel, parameters, event normalizer
    runtime/                # RuntimeBackend SPI + fail-closed StubRuntimeBackend
    resource/               # ResourceEnvelope estimator (advisory Plan only)
```

## Operation mapping (ENGINE-MLC §4)

| OmniLLM phase | MLC mapping (design) | Scaffold runtime |
|---|---|---|
| PROBE | package / target / runtime compatibility | Plan pure; execute → backend (`UNKNOWN` default) |
| LOAD | runtime + generated module load | Plan pure; commit → backend; missing native ≠ success |
| PLAN_INFERENCE | no KV mutation (ADR-002) | Pure envelope + digests |
| COMMIT_INFERENCE | chat/session create, prompt prep | One-shot commit journal; session opaque token |
| START / GENERATE | stream generation callbacks | EventNormalizer → catalog events; terminal unique |
| EMBED | pinned API only | Always `CAPABILITY_UNKNOWN` until cell PASS |
| CLOSE / UNLOAD | session / module release | Best-effort; worker death invalidates tokens |

## Phase cancellation (ENGINE-MLC §5)

| Phase | Expected (design) | Measured (scaffold) |
|---|---|---|
| PROBE | COOPERATIVE | UNKNOWN |
| LOAD | WORKER_KILL_ONLY | UNKNOWN |
| CREATE_SESSION / PLAN / COMMIT | COOPERATIVE | UNKNOWN |
| START | INTERRUPTIBLE | UNKNOWN |
| GENERATE | COOPERATIVE | UNKNOWN |
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
// reg.upstreamLocked == false (template lock)
// cells: UNQUALIFIED + evidence NOT_EXECUTED → project UNKNOWN
// never SUPPORTED without QUALIFIED_WITH_ENVELOPE + PASS
```

Unit / architecture wiring (not production attach):

```kotlin
val engine = MlcLlmModule.createEngine(
    backend = StubRuntimeBackend(exploratoryDryRun = false), // default: UNKNOWN execute
)
// Exploratory plumbing tests only:
val dry = MlcLlmModule.createEngine(
    backend = StubRuntimeBackend(exploratoryDryRun = true),  // CPU dry-run only
)
```

## Native / SDK integration guide (when human pins real artifacts)

Real MLC Android SDK / runtime is **not** vendored in this monorepo. Complete the
interfaces and wire a real backend as follows:

1. **Pin** complete `UPSTREAM.lock` (checklist above) and publish a new `EngineBuildId`.
2. **Package** runtime + generated model libs under `:android:native` with NDK r28+
   16 KB page-size alignment evidence; ABIs must match the lock.
3. **Implement** `RuntimeBackend` (e.g. `JniMlcRuntimeBackend`) that:
   - Loads only after privileged re-verify ticket + digest match
   - Returns opaque tokens (never raw `jlong` pointers across process)
   - Maps stream callbacks through `EventNormalizer`
   - Maps driver/worker death to `NativeErrorCode.DRIVER_CRASH` / `WORKER_CRASH`
   - On missing `.so` / ABI mismatch: return `NOT_AVAILABLE` / `UNKNOWN_CAPABILITY`
     — **never** treat missing natives as success
4. **Placement**: untrusted generated code → different package/UID companion
   (`EXTERNAL_UID_ACCELERATED`). If companion lacks GPU, combination is
   **unsupported** (no same-UID fallback).
5. **Offline compile** of models is a **Job** (job-manager), never an implicit
   inference request attribute (`offlineCompile` is unsupported-by-default).
6. **Qualify** per backend × device × driver × model × workload cell with
   measured cancellation + resource peaks; put `QUALIFIED_WITH_ENVELOPE` + `PASS`
   only with real evidence packs.
7. **EngineSelectionPolicy**: peer engines (including MLC-LLM) stay stub/UNKNOWN
   until policy + evidence allow real native attach.

## Known limitations / unsupported-by-default (ENGINE-MLC §10)

1. Any backend without pinned compiler/runtime/target is **`UNKNOWN`**.
2. Generated code raises isolation requirements above pure-weight formats.
3. GPU memory / cancellation / driver recovery are OEM-dependent — no global guarantee.
4. Prefix, embedding, multimodal, tool/structured require per model/API cell validation.
5. Old compiled artifacts are not assumed compatible with a new runtime (ENGINE-MLC §9).
6. Dry-load / exploratory stub **never** elevates model trust (INV-008).
7. No silent cross-revision or cross-backend evidence inheritance.

## Hard adapter rules (ENGINE-STANDARD §3)

Adapters must **not**:

- write OmniLLM DB / model store (ADR-010)
- return native pointers across process
- silently ignore unsupported parameters
- elevate trust via dry-load
- claim global support from a single device cell
- mutate KV / load large resources during Plan (ADR-002)
- claim `SUPPORTED` without QUALIFIED_WITH_ENVELOPE + PASS

## Tests

```bash
./gradlew :engines:mlc-llm:test
```

Coverage includes:

- Default stub → `CAPABILITY_UNKNOWN` on probe/load/start/embed
- Exploratory CPU Plan→Commit→Start→Close→Unload plumbing
- Accelerator backends remain UNKNOWN even in exploratory mode
- Mapping (errors, sanitize, events, phase cancel, params)
- Resource envelopes (CPU/GPU separation, conservative placeholders)
- Upstream lock completeness + parse
- Registry seed projects UNKNOWN, never SUPPORTED

## Remaining human-only work

- [ ] Pin complete `UPSTREAM.lock` (MLC + TVM + digests + observedAt)
- [ ] Real `RuntimeBackend` (JNI / official Android MLCEngine binding)
- [ ] 16 KB page-size packaging evidence via `:android:native`
- [ ] Per-backend/device/driver/model/workload qualification cells with PASS evidence
- [ ] Compiler reproducibility + code-signing supply-chain evidence pack
- [ ] Measured phase cancellation + resource peaks
- [ ] Companion GPU path for untrusted accelerated inference (ADR-007)
- [ ] Physical device / OEM matrix (out of software scaffold scope)
