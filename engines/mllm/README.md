# Engine Pack: mllm

| Field | Value |
|---|---|
| Module | `:engines:mllm` |
| Engine ID | `mllm` |
| Product doc | `ENGINE-MLLM` (`docs/80-engines/mllm.md`) |
| Standard | `ENGINE-STANDARD` |
| Design status | `BASELINE` |
| Upstream lock | `LOCKED` (mllm 2.0.0 + mllm-chat v2.0, 2026-08-09) |
| Qualification | `UNQUALIFIED` (no device evidence) |
| Registry exposure | `UNKNOWN` |
| Runtime capability default | `UNKNOWN` |

## Purpose

Device-local **client-server** adapter for [UbiquitousLearning/mllm](https://github.com/UbiquitousLearning/mllm) v2.0.0 via the upstream-prebuilt Go `mllm_server.aar` (Android distribution repo [mllm-chat](https://github.com/UbiquitousLearning/mllm-chat) v2.0).

Real backend ([`MllmServerBackend`](src/main/kotlin/com/omnillm/engines/mllm/server/MllmServerBackend.kt)):

- `gomllm.Gomllm.startServer(modelPath, ocrPath, tmpDir, enableProbing)` starts the OpenAI-compatible HTTP/SSE server on `127.0.0.1:8080` (port fixed upstream) and registers a session for the chat model directory (**server start = model load**; single model slot per process).
- Chat requests: `POST /v1/chat/completions` with `model` = model directory name, `messages` = prompt content, `stream: true`, optional `session_id`; SSE `data:` chunks carry `delta.content`.
- Cancellation: connection close (the server breaks its `r.Context()` poll loop) — no cancel RPC upstream (ENGINE-MLLM §6).

The embedded mllm server is an **engine-private** service:

- OmniLLM Adapter owns lifecycle, private channel, random runtime credential, and canonical request translation.
- The server is **not** exposed to external clients; HTTP/AIDL Gateway remains the only public entry (ADR-011).
- Loopback-only (`127.0.0.1:8080`); runtime credential is **adapter-enforced** (upstream server does not authenticate) — never Host/CORS alone.
- **Missing binary / AAR / jniLibs never loads as success** — execute paths fail closed.

## Package layout

```text
engines/mllm/
  README.md
  UPSTREAM.lock                 # LOCKED (mllm 2.0.0, mllm-chat v2.0 artifacts)
  capability-matrix.yaml        # all cells UNQUALIFIED / UNKNOWN (honest)
  build.gradle.kts              # android-library; AAR + pinned jniLibs fetch
  libs/mllm_server.aar          # upstream-prebuilt Go in-app server (tracked)
  src/main/AndroidManifest.xml
  src/main/kotlin/com/omnillm/engines/
    MllmModule.kt               # compatibility re-export
    mllm/
      MllmModule.kt             # factory + EngineRegistry registration (real default)
      MllmEngine.kt             # OmniEngine (plan pure; commit/query; path brokering)
      MllmLoadedModelPort.kt    # plan/commit/start/embed/close/unload
      lock/UpstreamLock.kt
      mapping/
        ErrorMapper.kt
        EventNormalizer.kt
        ParameterValidator.kt
        PhaseCancellation.kt
      resource/ResourceEnvelopeEstimator.kt
      server/
        PrivateChannelProtocol.kt   # channel policy + RPC catalog
        ServerBackend.kt            # private server SPI (real backend implements it)
        MllmServerBridge.kt         # gomllm.Gomllm bridge (device-only) + fake for tests
        MllmOpenAiProtocol.kt       # OpenAI-compatible body builder + SSE parser (pure)
        MllmHttpTransport.kt        # loopback HTTP/SSE transport (OkHttp)
        MllmServerBackend.kt        # REAL backend (replaces the stub as default)
        StubServerBackend.kt        # host unit-test helper only (never production)
      qualification/QualificationCells.kt
```

## Private channel protocol (software-complete)

See `PrivateChannelProtocol` / `ServerBackend`:

| Concern | Contract |
|---|---|
| Protocol id | `omnillm.mllm.private-channel` v1 |
| Preferred transport | Loopback HTTP/SSE (`127.0.0.1:8080`, upstream-fixed) |
| Localhost TCP | Loopback host only; runtime credential required (adapter-enforced) |
| Auth | Runtime-generated credential header; server itself does not authenticate |
| RPC methods | health, probe, load_model, create_session, generate, generate_cancel, embed, close_session, unload_model, commit_query, shutdown |
| Opaque handles | `ServerModelToken` / `ServerSessionToken` only — never raw pointers |
| Policy refuse | LAN bind, missing credential, UNKNOWN channel kind |

`MllmServerBackend` is the production default. `StubServerBackend` is kept for host unit tests only — never a silent production fallback.

## Artifact provisioning

- `mllm_server.aar` is **tracked** (`libs/`, sha256 in lock) — upstream commits it in the mllm-chat repo.
- Native CPU libs (`libMllmRT.so`, `libMllmCPUBackend.so`, `libMllmSdkC.so`, `libomp.so`, arm64-v8a) are **not tracked**: `libMllmRT.so` (146 MB) exceeds GitHub's 100 MB per-file limit. `build.gradle.kts` task `fetchMllmJniLibs` downloads the pinned release asset `jniLibs.zip` (v2.0) and fails closed on SHA-256 mismatch; the four CPU libs land in `build/mllm-jni/arm64-v8a`.
- QNN NPU libs are **not packaged**: upstream v2.0 ships them 4 KB page aligned only (repo 16 KB gate FAIL) — NPU stays `UNKNOWN`/unsupported-by-default.

## Operation mapping (ENGINE-MLLM §4)

| Omni operation | Adapter behavior (current pack) |
|---|---|
| PROBE | Pure plan + real loopback reachability probe under complete lock |
| LOAD | Pure plan (Go/server overhead envelope) + commit journal; real `Gomllm.startServer(modelPath, …)`; resolvedModelPath required (INV-010 broker) |
| PLAN inference | Pure; rejects unknown/unsupported params; unqualified prefix → UNKNOWN |
| COMMIT inference | One-shot / idempotent / queryable; session token created (server-side KV session) |
| START / GENERATE | OpenAI-compatible chat + SSE stream → catalog events; cancel = connection close; promptUtf8 resolution from digest is control-plane Stage-5 work (fails closed until then) |
| EMBED | `UNSUPPORTED_OPERATION` (upstream has no embedding API) |
| CLOSE / UNLOAD | CLOSE releases local handle (server has no close RPC); UNLOAD is `UNSUPPORTED_OPERATION` (no upstream unload API; process death reclaims) |

Plan has **no** domain / server mutation (ADR-002). Commit is one-shot, idempotent, queryable by `commitId` (ADR-004/005).

## Registry registration

Called from `EnginePackAttachment` (runtime control plane only — INV-001 / ADR-010):

```kotlin
val reg = MllmModule.registerWith(registry, seedCells = false)
MllmModule.seedUnqualifiedPlaceholders(
    registry = registry,
    engineBuildId = reg.engineBuildId,
    deviceFingerprint = deviceFingerprint,
)
// reg.designStatus == "BASELINE"
// reg.upstreamLocked == true (lock complete — supply chain only, not SUPPORTED)
// all cells: UNQUALIFIED + NOT_EXECUTED → CapabilityState.UNKNOWN
```

**Never** treat design-complete as runtime `SUPPORTED`. Only a non-expired PASS evidence cell with `QUALIFIED_WITH_ENVELOPE` may project `SUPPORTED`.

## Known limitations (ENGINE-MLLM §9, upstream-verified 2026-08-09)

1. Single chat-model slot per process; no stop API, no unload API, no embedding API upstream (honest errors, not stubs).
2. Server does not authenticate — runtime credential is adapter-enforced only; loopback port 8080 fixed upstream.
3. Cancellation is connection-close; native decode halt unproven — measure before claiming COOPERATIVE.
4. Tokenizer / KV / prefix / embedding / multimodal need API + cell evidence before publication.
5. Go/server overhead and shutdown latency must be measured; do not copy JNI envelope assumptions.
6. Server crash poisons its sessions; Runtime ledger owns client query answers.
7. QNN NPU libs not packaged (4 KB alignment only); OCR path single-turn/image-mandatory — unsupported-by-default.
8. Prompt content resolution (`canonicalInputDigest` → `promptUtf8`) is control-plane Stage-5 work; the backend fails closed with `INVALID_ARGUMENT` until wired.

## Hard rules

1. Incomplete `UPSTREAM.lock` ⇒ exploratory only; Registry stays `UNKNOWN`.
2. Plan has no domain mutation.
3. Dry-load / probe never elevates model trust.
4. Unsupported parameters fail closed (never silent ignore).
5. No native pointer / server internal handle across process boundaries (opaque handles only).
6. Do not invent types/enums/states absent from `specs/`.
7. UI never loads this engine (INV-001). Adapter never writes OmniLLM DB (ADR-010).
8. Missing AAR / jniLibs / resolved path never reports load success.

## Status

| Dimension | Value |
|---|---|
| Design | BASELINE (product docs complete) |
| Upstream lock | LOCKED (mllm 2.0.0 + mllm-chat v2.0, digests pinned 2026-08-09) |
| Implementation | Real Go-server backend (gomllm bridge + HTTP/SSE) — replaces stub default |
| Qualification | UNQUALIFIED for all cells (no device evidence) |
| Runtime claim | UNKNOWN only |

## Next implementation steps (human / device)

1. Wire control-plane prompt-content resolution (`canonicalInputDigest` → content) into the port's `start()` (Stage 5).
2. Instrumented tests on an arm64 device/emulator: server start, Qwen3 `.mllm` model load, streaming chat, cancel, crash recovery (Stage 5).
3. Measure phase cancellation + resource envelopes per backend/SoC (include Go fixed overhead).
4. Publish evidence cells; never inherit CPU evidence onto GPU/NPU.
5. Orphan/server death/port collision recovery evidence.
6. Physical device tests + OEM matrix (out of software-ready-to-launch scope).
