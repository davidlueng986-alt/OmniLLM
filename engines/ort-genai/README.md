# Engine Pack: ort-genai (ONNX Runtime GenAI)

| Field | Value |
|---|---|
| Module | `:engines:ort-genai` |
| Product design | `ENGINE-ORTGENAI` (`docs/80-engines/onnx-runtime-genai.md`) |
| Integration standard | `ENGINE-STANDARD` (`docs/80-engines/engine-integration-standard.md`) |
| `engineId` (registry) | `ONNX-Runtime-GenAI` |
| Design status | `BASELINE` (design-complete) |
| Upstream lock | `NOT_LOCKED` (template only) |
| Qualification | `UNQUALIFIED` |
| Runtime exposure | `UNKNOWN` (never `SUPPORTED` without cell evidence) |

## Purpose

Adapter pack for Microsoft [onnxruntime-genai](https://github.com/microsoft/onnxruntime-genai):
GenAI runtime API + execution-provider adapter shape.

This pack is **software-complete as a session adapter stub**: full OmniEngine
plan / commit / start / embed / close mapping, resource envelopes, phase
cancellation, error mapping, and Registry registration. It does **not** load
ORT/GenAI native libraries, does **not** claim provider support, and does
**not** write OmniLLM DB or model store (ENGINE-STANDARD §3 / ADR-010).

## Layout

```
engines/ort-genai/
  README.md                 # this file + native integration guide
  UPSTREAM.lock             # lock template (NOT_LOCKED) — human pin fields
  capability-matrix.yaml    # qualification cell placeholders (all UNQUALIFIED)
  build.gradle.kts
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
        GenAiBackend.kt     # opaque session adapter SPI
        StubGenAiBackend.kt # fail-closed default (no native load success)
```

## OmniEngine mapping

| OmniLLM phase | Upstream mapping (design) | Stub runtime |
|---|---|---|
| PROBE | package / provider presence | plan OK; execute → `CAPABILITY_UNKNOWN` |
| LOAD | model + generator params; provider init / graph opt / compile-cache | plan OK; commit → `CAPABILITY_UNKNOWN` |
| PLAN / COMMIT inference | session / sequence state (or worker-wrapped) | plan OK; commit → `CAPABILITY_UNKNOWN` |
| START / GENERATE | token generation | `CAPABILITY_UNKNOWN` |
| EMBED / multimodal / tools | only when API + model evidence | plan OK (envelope); commit → `CAPABILITY_UNKNOWN` |
| CLOSE / UNLOAD | drain native state | local bookkeeping OK (no native) |

If upstream has no separate plan/commit API, a future JNI adapter must wrap with
worker, qualified envelope, and ledger. Cells that cannot satisfy core
invariants are **not published** as supported.

## Upstream lock (ENGINE-STANDARD §4)

`UPSTREAM.lock` is a **template**. Empty digests / missing tag-commit keep
`lockState: NOT_LOCKED`. Builds without a complete lock are exploratory only.

Required for lock completeness (ENGINE-ORTGENAI §1):

- release/tag/commit
- sourceDigest + patchDigest (present, even if empty patches)
- ORT/GenAI artifacts + provider libraries digests (for EP cells)
- toolchain + ABI
- config schema pointer
- artifactDigest + engineBuildId
- observedAt
- license/notice digests

See `notes.humanPinSteps` in `UPSTREAM.lock` for the human pin sequence.
Updating any lock field ⇒ new `EngineBuildId`; old evidence does not auto-carry.

## Provider matrix

| Backend | Design | Runtime default | Notes |
|---|---|---|---|
| `cpu` | portable candidate | `UNKNOWN` | Primary Android candidate when EP packaged |
| `nnapi` | accelerator candidate | `UNKNOWN` | Android package + device evidence only |
| `qnn` | accelerator candidate | `UNKNOWN` | OEM/driver variance; no CPU inheritance |

Desktop EP support is **not** extrapolated to Android (ENGINE-ORTGENAI §3).

## Placement (ADR-007 / ENGINE-ORTGENAI §8)

| Trust case | Placement class |
|---|---|
| Verified runtime + provider + model + all phases qualified | privileged / crash-contained trusted (when measured cancel is privileged-safe) |
| Untrusted model or provider code | isolated CPU or different-UID companion (`EXTERNAL_UID_ACCELERATED`) |

Until phase cancellation is measured, prefer worker / isolated paths.
`UNKNOWN` cancellation is worker-only (fail-closed).

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
- Incomplete lock ⇒ exploratory only; Registry stays UNKNOWN
- Missing / unloaded natives must **not** report load success

## Native / SDK integration guide

Real ORT GenAI SDK is **not** wired in this monorepo (no Maven/native download
automation that requires human secrets). Complete the software adapter first,
then pin and package offline.

### Steps for a human integrator

1. **Pin upstream** — fill every field in `UPSTREAM.lock` (see human pin steps).
2. **Build ORT + GenAI for Android** — NDK 28.2.x, ABIs `arm64-v8a` (and others as needed), 16 KB page size.
3. **Package** under `:android:native` or an engine-local jni folder; verify page-size + SBOM.
4. **Implement** `JniGenAiBackend : GenAiBackend` mapping:
   - `OgaModel` / config → `loadModel` → opaque `GenAiModelToken`
   - `OgaGenerator` / sequences → `createSession` → opaque `GenAiSessionToken`
   - token stream → `GenAiStreamEvent` → `EventNormalizer`
   - EP selection from `backend` / `provider` attributes (fail closed on unknown EP)
5. **Wire** via `OrtGenaiModule.createEngine(backend = JniGenAiBackend(…))` only from `:runtime` / workers — never UI (INV-001).
6. **Qualify** per cell (device × driver × model × workload × phase); never invent PASS.
7. **Do not** claim `SUPPORTED` until Registry cells are `QUALIFIED_WITH_ENVELOPE` + `PASS`.

### `GenAiBackend` SPI summary

| Method | Role |
|---|---|
| `probe` | Bounded package/provider check |
| `loadModel` | Model + generator params + provider init (LOAD) |
| `createSession` | Sequence / generator state |
| `generate` | Token generation + cancel poll |
| `embed` | Default UNKNOWN until API+model cell |
| `closeSession` / `unloadModel` | Drain |

Default `StubGenAiBackend(exploratoryDryRun = false)` returns
`UNKNOWN_CAPABILITY` for all mutation paths — suitable for unit tests and
production registry attach without natives.

## Known limitations (ENGINE-ORTGENAI §10)

- Unpinned version / provider / model config cannot be a rebuild baseline.
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

No ORT or GenAI Maven/native dependency is wired until UPSTREAM.lock is complete
and packaging (ABI, 16 KB, SBOM) is proven.

## Tests

```text
./gradlew :engines:ort-genai:test
```

Coverage: lock template completeness, registry UNQUALIFIED seeding, pure plan,
fail-closed probe/commit/start/embed, parameter validation, resource envelopes,
phase cancellation UNKNOWN, exploratory dry-run plumbing (test-only).
