# Engine Pack: LiteRT-LM

| Field | Value |
|---|---|
| Module | `:engines:litert-lm` |
| Product doc | `ENGINE-LITERT` — `docs/80-engines/litert-lm.md` |
| Standard | `ENGINE-STANDARD` — `docs/80-engines/engine-integration-standard.md` |
| Design status | `BASELINE` |
| Qualification status | `UNQUALIFIED` |
| Registry exposure | `UNKNOWN` (never `SUPPORTED` without evidence) |
| Upstream | https://github.com/google-ai-edge/LiteRT-LM |
| Official Android docs | https://developers.google.com/edge/litert-lm/android |

## Purpose

Software-complete adapter pack that maps OmniLLM `OmniEngine` / `LoadedModelPort`
operations onto the official LiteRT-LM SDK / AAR **Engine / Conversation** surface.

This pack is **design-complete + adapter-complete** (interfaces, mapping, lock
template, registry seed, unit tests). It does **not** claim runtime `SUPPORTED`
for any backend × device × model × workload cell.

## Integration shape (ENGINE-LITERT §2)

- Prefer the official documented SDK / AAR form (`com.google.ai.edge.litertlm`).
- Do **not** assume a traditional JNI surface exposes every handle.
- Model artifacts: `.litertlm` or official typed packages described by
  `typedModelRevision` / model envelope.
- Adapter owns Engine / Conversation objects, stream callbacks, and backend
  configuration as **opaque** tokens.
- **Never** pass SDK objects across AIDL / process boundaries.
- Adapter **must not** write OmniLLM DB / model store (ENGINE-STANDARD §3, ADR-010).

## Package layout

```
engines/litert-lm/
  README.md
  UPSTREAM.lock              # template — NOT_LOCKED until human pin + digests
  capability-matrix.yaml     # design rows; all UNQUALIFIED / runtimeDefault UNKNOWN
  build.gradle.kts           # optional AAR via -Pomnillm.litertlm.sdkVersion
  src/main/kotlin/com/omnillm/engines/litertlm/
    LitertLmModule.kt        # factory + EngineRegistry registration
    LitertLmEngine.kt        # OmniEngine (plan/commit/query/bind)
    LitertLmLoadedModelPort.kt  # plan/commit/start/embed/close/unload + cancel
    lock/UpstreamLock.kt
    mapping/                 # ErrorMapper, EventNormalizer, ParameterValidator, PhaseCancellation
    resource/ResourceEnvelopeEstimator.kt
    sdk/
      SdkBackend.kt          # SPI
      StubSdkBackend.kt      # host unit tests only
      RealSdkBackend.kt      # production path; optional AAR
      LitertLmSdkBridge.kt   # Absent + Reflective bridge (no hard AAR dep)
      SdkBackendFactory.kt   # production vs host-test selection
```

## SDK backend selection

| Backend | When | Behavior |
|---|---|---|
| `StubSdkBackend` | Host unit tests via `SdkBackendFactory.forHostUnitTests()` | Dry-run plumbing; never elevates trust |
| `RealSdkBackend` | Production via `LitertLmModule.createProductionEngine()` | Fail closed if AAR absent; exploratory execute only when lock complete + policy flag |

**Hard rule:** production must **not** silently fall back from `RealSdkBackend` to
`StubSdkBackend` when the AAR is missing (INV-018). Missing AAR / natives ⇒
`NOT_AVAILABLE` / `CAPABILITY_UNKNOWN`, never success.

```kotlin
// Host tests
val eng = LitertLmModule.createEngine() // StubSdkBackend

// Runtime (still UNQUALIFIED; execute fail-closed unless policy + complete lock)
val prod = LitertLmModule.createProductionEngine(forceExploratory = false)
```

Registry attach (`EnginePackAttachment`) registers metadata + UNQUALIFIED cells only;
it does not load the AAR in the control plane.

## Human integration guide (pin official AAR)

The real SDK is **optional** so CI builds without Google Maven. To integrate:

### 1. Pin version (never `latest` for qualification)

1. Pick a release from
   [litertlm-android on Google Maven](https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android).
2. Record in `UPSTREAM.lock`:
   - `upstream.tag` / `upstream.commit`
   - `sdk.mavenCoordinate` e.g. `com.google.ai.edge.litertlm:litertlm-android:0.x.y`
   - `sdk.aarDigest` (SHA-256 of the AAR)
   - `sdk.nativeLibsDigest`, `sdk.apiSurfaceVersion`, `sdk.modelArtifactSchema`
   - `toolchain.*`, `build.abis`, `artifact.engineBuildId`, `artifact.artifactDigest`
   - `license.*`, `upstream.observedAt`, `upstream.patchDigest` (empty string if none)
3. Set `lockState: LOCKED` only when [UpstreamLock.isComplete] fields are filled.

### 2. Enable compile-time optional dependency (adapter module)

```bash
# local.properties or CI -P flag (example — replace with pinned version)
./gradlew :engines:litert-lm:compileKotlin -Pomnillm.litertlm.sdkVersion=0.0.0-REPLACE
```

With the property set, `build.gradle.kts` adds:

```kotlin
compileOnly("com.google.ai.edge.litertlm:litertlm-android:<version>")
```

`RealSdkBackend` also works via **reflection** when classes are only on the
runtime classpath (no compileOnly). Prefer compileOnly + a thin typed bridge
once the pin is stable.

### 3. Package AAR into the runtime process only (INV-001)

- Add `implementation("com.google.ai.edge.litertlm:litertlm-android:<pin>")` to
  **`:android:runtime-service`** (or companion worker) — **never** `:android:app-ui`.
- GPU: declare `libOpenCL.so` / `libvndksupport.so` as optional native libs in the
  runtime process manifest (see official docs).
- NPU: supply `npuNativeLibraryDir` via privileged attributes only.

### 4. Path broker (never raw client paths)

`RealSdkBackend` / reflective bridge refuse load without
`attributes["resolvedModelPath"]` from the **privileged path/FD broker** after
re-verify ticket. Client absolute paths must not cross AIDL.

### 5. Exploratory execute (policy)

Even with AAR present:

- Incomplete lock ⇒ load/generate stay `CAPABILITY_UNKNOWN`.
- Complete lock + `forceExploratory=true` may allow CONDITIONAL exploratory
  execute for packaged models when control-plane policy allows.
- **Capability matrix / qualification YAML remain UNQUALIFIED** until real
  device evidence packs land. AAR presence ≠ `SUPPORTED`.

### 6. Official API surface (reference)

From Google AI Edge docs (pin before coding against symbols):

```kotlin
import com.google.ai.edge.litertlm.*

val engine = Engine(EngineConfig(modelPath = resolvedPath, backend = Backend.CPU()))
engine.initialize()
engine.createConversation().use { conversation ->
  conversation.sendMessageAsync(prompt).collect { /* stream */ }
}
engine.close()
```

Map:

| SDK | OmniLLM |
|---|---|
| Engine init / close | LOAD commit / UNLOAD |
| createConversation / close | CREATE_SESSION / CLOSE |
| sendMessage / sendMessageAsync | START / GENERATE |
| (no qualified embed API) | EMBED → CAPABILITY_UNKNOWN |

## Upstream lock

See [UPSTREAM.lock](./UPSTREAM.lock). Incomplete lock ⇒ exploratory only;
`EngineRegistration.upstreamLocked == false` and Registry cells stay
`UNQUALIFIED` / project `UNKNOWN`.

Required for qualification eligibility (ENGINE-LITERT §1):

- repository, release/tag/commit
- SDK / AAR digest, native libs, toolchain, ABI
- model artifact schema, observedAt, license / notice / SBOM digests

## Operation mapping (ENGINE-LITERT §4)

| Omni operation | Adapter behavior |
|---|---|
| PROBE | Pure plan; RealSdkBackend probes AAR presence (not device SUPPORTED) |
| LOAD | Pure plan + commit ledger; Real fails closed without lock/policy/path broker |
| CREATE / SELECT SESSION | Opaque conversation id; no cross-process reuse |
| PLAN / COMMIT inference | Plan pure (ADR-002); commit one-shot / queryable; maxTokens captured for start |
| START / GENERATE | Stream → catalog events; `requestCancel` cooperative; unproven cancel ⇒ killable worker |
| EMBED | Fail closed (`CAPABILITY_UNKNOWN`) until cell evidence |
| CLOSE / UNLOAD | Best-effort; worker death invalidates all conversations |

If the pinned SDK binds plan and mutation in one API, the adapter wraps with a
conservative envelope + commit ledger. Combinations that cannot provide a
pre-mutation plan **must not** enter core qualification.

## Phase cancellation (ENGINE-LITERT §6)

Measured modes default **UNKNOWN**. Design expectations live in
`capability-matrix.yaml` / `PhaseCancellationMap.expectedModes`.

- SDK cancel that only stops future output ≠ native execution stopped.
- Terminal only after output fenced + session disposition + allocation accounting.
- UNKNOWN measured mode ⇒ full LoadedModel lifecycle on a killable worker.

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

- Prefix truncate / fork, cross-session reuse, embedding, multimodal, and
  structured output require SDK version + model cell evidence.
- Vendor / NPU memory and cancel observability may be insufficient → conservative
  envelopes and killable workers.
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
| Upstream lock | NOT_LOCKED (template — human pin required) |
| Implementation | Software-complete adapter + optional RealSdkBackend |
| Qualification | UNQUALIFIED for all cells |
| Runtime claim | UNKNOWN only |

## Residual human-only items

1. Pin LiteRT-LM release/tag/commit + AAR/native digests in `UPSTREAM.lock`.
2. Enable `-Pomnillm.litertlm.sdkVersion` and package AAR into runtime process.
3. Wire privileged path/FD broker → `resolvedModelPath` attributes.
4. Measure phase cancellation + resource envelopes on real devices.
5. Publish evidence cells (never inherit CPU evidence onto GPU/NPU).
6. Physical device / OEM matrix and Play submission (out of this pack’s scope).
