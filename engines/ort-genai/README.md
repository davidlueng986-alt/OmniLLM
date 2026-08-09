# Engine Pack: ort-genai (ONNX Runtime GenAI)

| Field | Value |
|---|---|
| Module | `:engines:ort-genai` |
| Product design | `ENGINE-ORTGENAI` (`docs/80-engines/onnx-runtime-genai.md`) |
| Integration standard | `ENGINE-STANDARD` (`docs/80-engines/engine-integration-standard.md`) |
| `engineId` (registry) | `ONNX-Runtime-GenAI` |
| Design status | `BASELINE` (design-complete) |
| Upstream lock | `LOCKED` (v0.14.0 AAR; see `UPSTREAM.lock`) |
| Integration status | `INTEGRATED` (real backend, compile-verified) |
| Qualification | `UNQUALIFIED` (no device evidence — honest) |
| Runtime exposure | `UNKNOWN` (never `SUPPORTED` without cell evidence) |

## Purpose

Adapter pack for Microsoft [onnxruntime-genai](https://github.com/microsoft/onnxruntime-genai):
GenAI runtime API + execution-provider adapter shape.

This pack now contains a **real backend** (`RealGenAiBackend`) over the official
`ai.onnxruntime.genai` Java API from the pinned Android AAR (v0.14.0) — direct
JVM access, no OmniLLM JNI bridge. Full OmniEngine plan / commit / start /
embed / close mapping, resource envelopes, phase cancellation, error mapping,
and Registry registration. It does **not** claim provider support, does **not**
write OmniLLM DB or model store (ENGINE-STANDARD §3 / ADR-010), and stays
`UNQUALIFIED` until device-verified inference evidence exists (Stage 5).

## Layout

```
engines/ort-genai/
  README.md                 # this file + integration guide
  UPSTREAM.lock             # LOCKED (v0.14.0 AAR + base ORT 1.25.1 pins)
  capability-matrix.yaml    # qualification cells (all UNQUALIFIED; INTEGRATED noted)
  build.gradle.kts          # compileOnly real API classes (libs/…jar, inline pin)
  libs/
    onnxruntime-genai-android-0.14.0.jar  # classes.jar extracted from pinned AAR
  src/main/kotlin/com/omnillm/engines/
    OrtGenaiModule.kt       # compatibility re-export
    ortgenai/
      OrtGenaiModule.kt     # factory + EngineRegistry registration
      OrtGenaiEngine.kt     # OmniEngine (plan/probe/load)
      OrtGenaiLoadedModelPort.kt  # plan/commit/start/embed/close/unload
      lock/UpstreamLock.kt  # lock parse + completeness gate + provider digests
      mapping/
        PhaseCancellation.kt
        ErrorMapper.kt
        EventNormalizer.kt
        ParameterValidator.kt
      resource/ResourceEnvelopeEstimator.kt
      session/
        GenAiBackend.kt           # opaque session adapter SPI
        GenAiRuntime.kt           # native seam (Model/Params/Generator)
        OrtGenAiRuntime.kt        # direct ai.onnxruntime.genai binding
        RealGenAiBackend.kt       # production backend (real calls)
        GenAiBackendFactory.kt    # create / forHostUnitTests / isRuntimeAvailable
        StubGenAiBackend.kt       # host-unit-test only (never production default)
```

## OmniEngine mapping

| OmniLLM phase | Upstream mapping | Real backend (v0.14.0 Java API) |
|---|---|---|
| PROBE | package / provider presence | native-load probe; natives absent ⇒ `NOT_AVAILABLE` |
| LOAD | model + generator params; provider init / graph opt / compile-cache | `Model(modelDir)` + `Tokenizer` + `GeneratorParams` from ONNX GenAI folder (`genai_config.json` required); fail-closed when unproven or natives absent |
| PLAN / COMMIT inference | session / sequence state (or worker-wrapped) | session = `GeneratorParams` holder; commit gated by lock + exploratory policy |
| START / GENERATE | token generation | token loop: `generateNextToken()` + `TokenizerStream` decode; stop on EOS / MAX_TOKENS / cooperative cancel |
| EMBED / multimodal / tools | only when API + model evidence | `CAPABILITY_UNKNOWN` (Java API has no embedding surface) |
| CLOSE / UNLOAD | drain native state | `close()` on generator / params / model (KV cache released) |

If upstream has no separate plan/commit API, the adapter wraps with worker,
qualified envelope, and ledger. Cells that cannot satisfy core invariants are
**not published** as supported.

## Upstream lock (ENGINE-STANDARD §4)

`UPSTREAM.lock` is **LOCKED** (2026-08-09):

- `microsoft/onnxruntime-genai` tag `v0.14.0` @ `b7a6ec30…`; artifact =
  official GitHub release AAR `onnxruntime-genai-android-0.14.0.aar`
  (sha256 `c2e9b967…`, GitHub-published digest re-verified from bytes).
- **Not on Maven Central** — the artifact is a GitHub release asset; the JVM
  Java API "package publication is pending". Base runtime dependency:
  `com.microsoft.onnxruntime:onnxruntime-android:1.25.1` (Maven Central).
- Verified facts: minSdk 24; ABIs `arm64-v8a` + `x86_64`; 16 KB ELF alignment
  PASS (4/4 genai AAR, 8/8 base AAR); MIT license.
- Lock completeness ≠ qualification: cells stay `UNQUALIFIED / NOT_EXECUTED`.

Updating any lock field ⇒ new `EngineBuildId`; old evidence does not auto-carry.

## Provider matrix

| Backend | Design | Runtime default | Notes |
|---|---|---|---|
| `cpu` | portable candidate | `UNKNOWN` | CPU EP statically linked in pinned AAR; device evidence still required |
| `nnapi` | accelerator candidate | `UNKNOWN` | Android package + device evidence only |
| `qnn` | accelerator candidate | `UNKNOWN` | OEM/driver variance; GenAI 0.14.0 dropped QNN from the AAR |

Desktop EP support is **not** extrapolated to Android (ENGINE-ORTGENAI §3).

## Placement (ADR-007 / ENGINE-ORTGENAI §8)

| Trust case | Placement class |
|---|---|
| Verified runtime + provider + model + all phases qualified | privileged / crash-contained trusted (when measured cancel is privileged-safe) |
| Untrusted model or provider code | isolated CPU or different-UID companion (`EXTERNAL_UID_ACCELERATED`) |

Until phase cancellation is measured, prefer worker / isolated paths.
`UNKNOWN` cancellation is worker-only (fail-closed). The Java bindings expose
no native cancel API — cancellation is cooperative polling between tokens, so
an in-flight generate is not preemptible (worker-kill-only until measured).

## Registry registration

`EnginePackAttachment` (runtime control plane) calls:

```text
OrtGenaiModule.registerDesignCompleteUnqualified(registry, deviceFingerprint, …)
```

Rules:

```text
designStatus: BASELINE          ≠  runtime SUPPORTED
qualificationStatus: UNQUALIFIED
registryExposure: UNKNOWN
runtimeCapabilityDefault: UNKNOWN
```

Only `QUALIFIED_WITH_ENVELOPE` + evidence `PASS` may project `SUPPORTED`.
Seeding from `capability-matrix.yaml` inserts **UNQUALIFIED / NOT_EXECUTED** cells only.

## Hard rules (adapter)

- Never write OmniLLM DB / model store
- Never return native pointers across process
- Never silently ignore unsupported parameters
- Plan has no domain mutation (ADR-002)
- Dry-load / probe never elevates model trust (INV-008)
- Natives absent ⇒ `NOT_AVAILABLE` — never a load-success report
- AAR presence ≠ SUPPORTED; exploratory gate required for execute
- Prompt content never synthesized from a digest — `promptUtf8` or staged registry only

## Integration guide

### Current state (Stage 2E)

- `RealGenAiBackend` + `OrtGenAiRuntime` compiled **against the real
  `ai.onnxruntime.genai` classes** (from `libs/onnxruntime-genai-android-0.14.0.jar`
  = classes.jar extracted from the pinned AAR; sha256 in `UPSTREAM.lock`).
- Module builds `compileKotlin` / `test` / `jar` green; `:engines:ort-genai:test`
  exercises real native-availability detection (host JVM: fail-closed) + full
  orchestration logic against a fake runtime.
- Host JVM **cannot** run real inference: the AAR `.so` are Android ELF, and no
  JVM GenAI artifact exists on Maven Central. Real-inference smoke needs
  provisioning (below).

### Stage 5 (device qualification) checklist

1. **Android packaging** — consuming module `:android:runtime-service` adds:
   - `implementation(files("…/onnxruntime-genai-android-0.14.0.aar"))`
     (download URL in `UPSTREAM.lock`; sha256 `c2e9b967…`)
   - `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.1")`
   - verify AAR jni packaging + 16 KB alignment (ELF check already green).
2. **Model provisioning** — ONNX GenAI folder (`genai_config.json` + `.onnx` +
   tokenizer) into app-private storage; a tiny fixture exists upstream:
   `test/test_models/hf-internal-testing/tiny-random-gpt2-fp32` (~3.5 MB) at the
   pinned commit. Control plane resolves broker key → path via
   `modelDirProvider` / `modelDir` attribute.
3. **Instrumented tests** — `androidTest` on device/emulator running
   `RealGenAiBackend` end-to-end (or the gated
   `provisionedRealInference_smoke` with
   `-Pomnillm.ortgenai.smokeModelDir=<folder>` on a JVM GenAI build).
4. **Measure** phase cancellation / resource peaks; capture cell evidence
   (device × driver × model × workload), then flip cells with PASS records.
5. Prompt wiring: control plane supplies `promptUtf8` (or `stagePrompt` by
   digest) when calling `start` — the SPI stays digest-only at plan level.

### `GenAiBackend` SPI summary

| Method | Role |
|---|---|
| `probe` | Bounded package/provider check (native-load probe) |
| `loadModel` | Model + tokenizer + generator params (LOAD) |
| `createSession` | `GeneratorParams` holder |
| `generate` | Token generation + cancel poll |
| `embed` | UNKNOWN until API+model cell |
| `closeSession` / `unloadModel` | Drain (close() releases KV) |

`StubGenAiBackend` remains for host unit tests only (`GenAiBackendFactory.forHostUnitTests`);
`OrtGenaiModule.createEngine` defaults to the **real** backend (fail-closed).

## Known limitations (ENGINE-ORTGENAI §10)

- No JVM (non-Android) GenAI artifact — host real-inference smoke requires an
  upstream JVM build or a device/emulator (Stage 5).
- v0.14.0 Java API has no `OrtGenAI`/`Model.load(path, sessionOptions)` and no
  separate KV-release API — streaming via `Generator` + `TokenizerStream`; KV
  freed by `close()`. (Design doc §1/§5 assumed the older surface.)
- Provider capability, cancel, and memory differ widely across Android/OEM — per-cell qualification only.
- Prefix/KV, embedding, multimodal, tool/structured capabilities are **not** inferred from ONNX format alone.
- Config + external-data packages have high input complexity; need bounded parser + full manifest.
- Free-form JSON missing required fields or unknown major → fail closed.
- Generator / sequence / native objects never cross AIDL; no raw native pointers on wire.

## Qualification closure (future)

Per ENGINE-ORTGENAI §11: pinned artifacts/provider matrix, config corpus,
session/tokenizer, phase cancel/resource, event/error, reply-loss/crash,
16 KB packaging, license/SBOM — then publish **per cell**.

## Dependencies

- `api(project(":engines:api"))` — OmniEngine SPI only
- Core contracts / errors / resource / identity / state / canonical
- `compileOnly` + `testImplementation`: `libs/onnxruntime-genai-android-0.14.0.jar`
  (real API classes; inline pin — do not move to `libs.versions.toml`)

## Tests

```text
./gradlew :engines:ort-genai:test
```

Coverage: real native-availability fail-closed (host JVM), load/session/generate
orchestration (fake runtime), token-loop stop conditions (MAX_TOKENS/EOS/cancel),
staged-prompt registry, search-option mapping, cleanup, lock LOCKED parse,
registry UNQUALIFIED seeding, pure plan, parameter validation, resource
envelopes, phase cancellation UNKNOWN, exploratory dry-run plumbing (test-only).
Real-inference smoke is assume-gated on provisioned natives + model (Stage 5).
