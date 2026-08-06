# AGENTS.md — OmniLLM Android monorepo

Instructions for humans and coding agents working in this repository.

## Authority order

1. User / task instructions in the current session.
2. Machine-readable `specs/` catalogs (types, errors, states, capabilities, OpenAPI, AIDL, SQL).
3. Product normative docs (when accessible): invariants, ADRs, ANDROID-BASELINE.
4. This file and module `build.gradle.kts` dependency edges.
5. Implementation notes under `docs/architecture/`.

**Never invent** types, enums, states, error codes, or capability IDs absent from `specs/`. Prefer codegen from specs over hand-written duplicates.

## Module map

### `core/*` — portable pure Kotlin

| Module | Responsibility |
|---|---|
| `:core:canonical` | Canonical types, encoding, digests, total-order collections (INV-015) |
| `:core:state` | State machine definitions / pure transitions from `specs/state-machines.yaml` |
| `:core:contracts` | Plan / Reserve / Commit / Execute contracts; operation envelopes |
| `:core:errors` | Error catalog mapping from `specs/error-catalog.yaml` |
| `:core:resource` | ResourceVector, reservation math, conservation checks |
| `:core:identity` | Blob / ArtifactPackage / ModelRevision / Installation identities (ADR-008) |

**Rules:** No Android SDK, no Room, no engine native code, no I/O side effects in pure functions.

### `data/*` — durable state (control-plane write only)

| Module | Responsibility |
|---|---|
| `:data:persistence` | Schema, migrations, repositories (single writer) |
| `:data:model-store` | Content-addressed blobs / installations |

**Rules:** Only runtime control plane may write (ADR-010). Workers receive FDs / narrow IPC — never open secrets or catalog writable paths.

### `runtime/*` — control plane

| Module | Responsibility |
|---|---|
| `:runtime:request-registry` | Idempotency claim-or-return, attempt, terminal, query (ADR-004/005) |
| `:runtime:orchestrator` | Capability filter, plan, route, schedule, fallback policy |
| `:runtime:governor` | Multi-dimensional resource reservation / pressure / eviction barrier |
| `:runtime:session` | Session/KV ownership, pool, poison/drain (INV-007) |
| `:runtime:model-manager` | Acquisition, trust, installation, load lifecycle |
| `:runtime:job-manager` | Recoverable jobs (download/import/benchmark/delete) |
| `:runtime:policy` | Settings, ACL, revocation epoch, risk acknowledgment |
| `:runtime:observability` | Structured logs, redaction, traces, diagnostics export hooks |

**Rules:** Plan has **no** domain mutation (ADR-002 / INV-002–003). Unknown capability / cancel / envelope ⇒ fail closed (INV-018).

### `engines/*` — Engine Packs

| Module | Responsibility |
|---|---|
| `:engines:api` | Engine adapter SPI, phase capability, event normalization |
| `:engines:llama-cpp` | llama.cpp adapter |
| `:engines:litert-lm` | LiteRT-LM adapter |
| `:engines:mlc-llm` | MLC-LLM adapter |
| `:engines:mllm` | mllm adapter |
| `:engines:ort-genai` | ONNX Runtime GenAI adapter |

**Rules:** Adapters translate upstream runtimes; they **must not** redefine canonical semantics or Orchestrator/transport contracts (`ARCH-EXTENSION` Engine Pack).

### `interfaces/*` — transports

| Module | Responsibility |
|---|---|
| `:interfaces:http` | HTTP gateway / OpenAPI projection |
| `:interfaces:aidl` | Binder Runtime Binding projection |
| `:interfaces:admin` | Admin facade for UI / local administration |

**Rules:** Same canonical request/event/error; transport delivery guarantees differ and must be explicit (ADR-011 / INV-013). No engine selection or Session mutation inside transport adapters.

### `features/*` — Feature Packs

| Module | Feature doc (product) |
|---|---|
| `:features:auto-setup` | FEAT auto-setup |
| `:features:modelhub` | ModelHub acquisition |
| `:features:playground` | Local playground |
| `:features:server` | Developer server |
| `:features:lan` | LAN access |
| `:features:dashboard` | Dashboard / monitoring |
| `:features:benchmark` | Benchmark / research |
| `:features:diagnostics` | Diagnostics export |
| `:features:routing` | Multi-model routing |
| `:features:tools` | Structured tools |
| `:features:admin` | Administration / jobs UI-domain |
| `:features:ai-content-report` | Play AI content reporting |

**Rules:** Features depend on capabilities, not engine-private knobs. Engine-specific params go in capability-scoped extension namespaces and LoadKey/profile (`ARCH-EXTENSION` Feature Pack).

### `android/*` — process / OS adapters

| Module | Process role |
|---|---|
| `:android:app-ui` | UI main process — **no** native engines, **no** DB writes (INV-001) |
| `:android:runtime-service` | Runtime control plane process (FGS, gateway, single writer) |
| `:android:workers` | Same-UID crash workers / background work |
| `:android:parser-isolated` | `isolatedProcess` parser / restricted CPU path |
| `:android:companion-sandbox` | Different-package UID companion for untrusted acceleration (ADR-007) |
| `:android:native` | NDK packaging, ABI / 16 KB validation glue |

## Dependency rules (inward only)

```text
Experience (app-ui, feature UI)
  → Interface / Admin view models
    → Canonical types + Control plane (runtime/*)
      → Engine abstraction (engines:api) + Data + Platform ports
        → Engine adapters (engines:*)
        → OS / Android adapters (android/* except app-ui)
```

### Forbidden edges

| From | Must not depend on |
|---|---|
| `:android:app-ui` | `:engines:*`, `:data:*` writers, native `.so` loaders |
| `:engines:*` | `:data:persistence` Room DAOs, secrets stores |
| `:interfaces:http` | native pointers, direct Session mutation |
| any worker / isolated / companion | catalog writable path, token vault, privileged secrets |
| Feature Pack | raw engine C API bypassing `:engines:api` |

### Allowed examples

- `:runtime:orchestrator` → `:core:contracts`, `:core:resource`, `:engines:api`
- `:interfaces:admin` → `:core:*`, `:runtime:*` (API surfaces only)
- `:android:runtime-service` → `:runtime:*`, `:data:*`, `:interfaces:*`, `:engines:*`
- `:android:app-ui` → `:interfaces:admin`, `:core:canonical` (view projections only)

Gradle modules must encode these edges in `dependencies {}`. CI fails closed via `checkModuleDependencyRules` + `checkDependencyEdges`.

## How to add a Feature Pack

1. **Product first:** design under product `docs/70-features/` + update capability maps in `specs/` if new capabilities are required. Do not invent capability IDs in code first.
2. **Module:** create `features/<name>/` with `build.gradle.kts` (copy a peer Feature Pack).
3. **Register:** `include(":features:<name>")` in `settings.gradle.kts`.
4. **Dependencies:** depend on `:core:*` + needed `:runtime:*` / `:interfaces:admin` APIs only. No direct `:engines:llama-cpp` unless the feature is explicitly engine-qualification UI.
5. **Contracts:** feature-scoped state, jobs, and errors must map to catalogs; unknown paths fail closed.
6. **UI:** Compose / screens live in `:android:app-ui` or a thin feature UI submodule later; they talk only through Admin facade (INV-001).
7. **Docs:** add a short note under `docs/architecture/` if process topology or new IPC is introduced.
8. **Tests:** unit tests for pure logic; instrumentation only where Android APIs are required.

Template reference: product `templates/feature-design-template.md`.

## How to add an Engine Pack

1. **Product first:** design under product `docs/80-engines/` + qualification schema (`specs/engine-qualification-*.yaml`).
2. **Module:** create `engines/<name>/` implementing `:engines:api` SPI only.
3. **Register:** `include(":engines:<name>")` in `settings.gradle.kts`.
4. **Placement:** declare process placement (in-process trusted / worker / isolated / companion). Untrusted accelerated paths **must** use `:android:companion-sandbox` (ADR-007 / INV-009).
5. **Semantics:** map upstream events to canonical events/errors; do not change Orchestrator or HTTP/AIDL wire meaning.
6. **Native:** ship `.so` via `:android:native` or engine-local jni; all ABIs under 16 KB page-size verification (`ANDROID-NATIVE`).
7. **Resources:** publish ResourceVector envelope and cancellation phases; Plan remains pure.
8. **Evidence:** qualification status is evidence-driven; dry-load/benchmark does not elevate trust (INV-008).

Template reference: product `templates/engine-design-template.md`.

## Specs and codegen

- Treat `specs/` as read-mostly authority. Prefer re-copy from the product package over hand edits.
- Codegen tools belong in `tools/codegen/` and should emit into `core/` or dedicated `generated/` source sets.
- OpenAPI: `specs/openapi/omnillm.openapi.yaml` → packaged in `:interfaces:http` (`openapi/omnillm.openapi.yaml`); Ktor stubs in `OmniHttpRoutes`.
- AIDL catalog: `specs/aidl/omnillm-aidl.yaml` → `tools/codegen/extract_aidl.py` → `:interfaces:aidl` `src/main/aidl/`.
- Schema: `specs/database/omnillm-schema.sql` → `:data:persistence` resources + SQLDelight subset; **control plane sole writer** (ADR-010).
- Integration notes: `docs/architecture/contract-integration.md`.

## Security & process reminders

- Client generates `requestId` / `idempotencyKey` before send; server claim-or-return; on reply loss **query**, do not blindly replay (ADR-004/005).
- Privileged load re-verifies FD content identity, signature chain, revocation (INV-010).
- AIDL principal = calling UID / Android user + verified binding — never caller self-reported package (INV-011).
- Token/trust/ACL/risk revocation bumps epoch and fences active/queued/pooled state (INV-017).

## Build / toolchain

See [README.md](./README.md) for version catalog locks (JDK 17, AGP 9.3.0, Gradle 9.5.0, compile/target 36, minSdk 28, NDK 28.2.x).

Play deploy readiness (policy locks, not fake evidence):

- [gradle/RELEASE_CHECKLIST.md](./gradle/RELEASE_CHECKLIST.md) — manual Console + build gates
- [android/app-ui/play/DATA_SAFETY_INVENTORY.md](./android/app-ui/play/DATA_SAFETY_INVENTORY.md)
- [android/companion-sandbox/PACKAGING.md](./android/companion-sandbox/PACKAGING.md) — separate UID APK

## What agents must not do

- Modify the product document package from this repo tasking.
- Expand scope into full feature implementation unless the task asks for it.
- Add dependency edges that violate INV-001 or single-writer rules “for convenience”.
- Commit secrets, model weights, or large binaries without explicit policy.
