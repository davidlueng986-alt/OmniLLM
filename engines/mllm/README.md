# Engine Pack: mllm

| Field | Value |
|---|---|
| Module | `:engines:mllm` |
| Engine ID | `mllm` |
| Product doc | `ENGINE-MLLM` (`docs/80-engines/mllm.md`) |
| Standard | `ENGINE-STANDARD` |
| Design status | `BASELINE` |
| Upstream lock | `NOT_LOCKED` (template) |
| Qualification | `UNQUALIFIED` |
| Registry exposure | `UNKNOWN` |
| Runtime capability default | `UNKNOWN` |

## Purpose

Device-local **client-server** adapter for [UbiquitousLearning/mllm](https://github.com/UbiquitousLearning/mllm) via Go `mllm_server.aar`.

The embedded mllm server is an **engine-private** service:

- OmniLLM Adapter owns server lifecycle, private channel, random runtime credential, and canonical request translation.
- The server is **not** exposed to external clients; HTTP/AIDL Gateway remains the only public entry (ADR-011).
- Prefer private IPC / Unix domain sockets. If only localhost TCP is available: port isolation, Host/auth, orphan cleanup — **never** Host/CORS alone.
- **Missing binary / AAR never loads as success** — execute paths fail closed with `CAPABILITY_UNKNOWN`.

## Package layout

```text
engines/mllm/
  README.md
  UPSTREAM.lock                 # human pin template (NOT_LOCKED)
  capability-matrix.yaml        # all cells UNQUALIFIED / UNKNOWN
  build.gradle.kts
  src/main/kotlin/com/omnillm/engines/
    MllmModule.kt               # compatibility re-export
    mllm/
      MllmModule.kt             # factory + EngineRegistry registration
      MllmEngine.kt             # OmniEngine (plan pure; commit/query; fail-closed)
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
        ServerBackend.kt            # private server SPI
        StubServerBackend.kt        # fail-closed without binary
      qualification/QualificationCells.kt
```

## Private channel protocol (software-complete)

See `PrivateChannelProtocol` / `ServerBackend`:

| Concern | Contract |
|---|---|
| Protocol id | `omnillm.mllm.private-channel` v1 |
| Preferred transport | Unix domain / private IPC |
| Localhost TCP | Opt-in only; loopback host; runtime credential required |
| Auth | Runtime-generated credential + runtime epoch headers |
| RPC methods | health, probe, load_model, create_session, generate, generate_cancel, embed, close_session, unload_model, commit_query, shutdown |
| Opaque handles | `ServerModelToken` / `ServerSessionToken` only — never raw pointers |
| Policy refuse | LAN bind, missing credential, UNKNOWN channel kind |

`StubServerBackend` implements the SPI without opening sockets. Production AAR wiring is `TODO(AAR)` behind the same interface.

## Operation mapping (ENGINE-MLLM §4)

| Omni operation | Adapter behavior (current pack) |
|---|---|
| PROBE | Pure plan + fail-closed execute unless exploratory + complete lock |
| LOAD | Pure plan (includes Go/server overhead envelope) + commit journal |
| PLAN inference | Pure; rejects unknown/unsupported params; unqualified prefix → UNKNOWN |
| COMMIT inference | One-shot / idempotent / queryable; unproven → ABORTED + CAPABILITY_UNKNOWN |
| START / GENERATE | Maps private-channel stream → catalog events; cancel needs RPC ack or worker kill |
| EMBED | Plan envelope only; commit fail-closed until API + cell evidence |
| CLOSE / UNLOAD | Best-effort; server crash poisons all sessions (Runtime ledger owns query) |

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
// reg.upstreamLocked == false (template lock incomplete)
// all cells: UNQUALIFIED + NOT_EXECUTED → CapabilityState.UNKNOWN
```

**Never** treat design-complete as runtime `SUPPORTED`. Only a non-expired PASS evidence cell with `QUALIFIED_WITH_ENVELOPE` may project `SUPPORTED`.

## UPSTREAM.lock human pin

Fill `engines/mllm/UPSTREAM.lock` (see checklist comments in file). Completeness requires:

- repository + tag/commit + sourceDigest + patchDigest field
- goVersion + toolchainDigest + abis
- artifactDigest (AAR/native) + **serverProtocolDigest** + engineBuildId
- observedAt + licenseDigest

Incomplete lock ⇒ exploratory only; Registry stays `UNKNOWN`.

## Integration guide (when real SDK / AAR cannot be downloaded)

This pack is **software-complete without binary**:

1. Keep `StubServerBackend` as default in production attach (already: registry metadata only; no real load).
2. Do **not** set `allowUnprovenExecution = true` outside controlled unit tests.
3. When AAR becomes available:
   - Pin tag/commit + digests in `UPSTREAM.lock`
   - Implement `AarServerBackend : ServerBackend` that:
     - starts Go server under worker process
     - opens UDS/private IPC (or isolated localhost + credential)
     - maps RPC/SSE → `ServerStreamEvent`
     - never binds LAN / never exposes management without auth
   - Measure cancellation + resource envelopes (include Go fixed overhead)
   - Publish evidence cells only after PASS packs — never invent SUPPORTED
4. 16 KB page-size packaging evidence required before Android production claim.

## Known limitations (ENGINE-MLLM §9)

1. Without idempotent server commit/query, mutations stay on a controlled worker with conservative recovery — no exactly-once claim.
2. Localhost ports may be attacked by other on-device apps; private channel or strong credential required.
3. Tokenizer / KV / prefix / embedding / multimodal need API + cell evidence before publication.
4. Go/server overhead and shutdown latency must be measured; do not copy JNI envelope assumptions.
5. Server crash poisons all its sessions; Runtime ledger owns client query answers.
6. HTTP socket close does **not** prove native stop.

## Hard rules

1. Incomplete `UPSTREAM.lock` ⇒ exploratory only; Registry stays `UNKNOWN`.
2. Plan has no domain mutation.
3. Dry-load / probe never elevates model trust.
4. Unsupported parameters fail closed (never silent ignore).
5. No native pointer / server internal handle across process boundaries (opaque handles only).
6. Do not invent types/enums/states absent from `specs/`.
7. UI never loads this engine (INV-001). Adapter never writes OmniLLM DB (ADR-010).
8. Missing AAR / native never reports load success.

## Status

| Dimension | Value |
|---|---|
| Design | BASELINE (product docs complete) |
| Upstream lock | NOT_LOCKED (template) |
| Implementation | Software-complete adapter + private-channel SPI + stub backend |
| Qualification | UNQUALIFIED for all cells |
| Runtime claim | UNKNOWN only |

## Next implementation steps (human / device)

1. Pin mllm tag/commit + build `mllm_server.aar` with Go/NDK digests.
2. Wire real `ServerBackend` private channel under worker process.
3. Measure phase cancellation + resource envelopes per backend/SoC.
4. Publish evidence cells; never inherit CPU evidence onto GPU/NPU.
5. Orphan/server death/port collision recovery evidence.
6. Physical device tests + OEM matrix (out of software-ready-to-launch scope).
