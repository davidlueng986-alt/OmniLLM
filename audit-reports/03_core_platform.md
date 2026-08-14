# 03 — CORE PLATFORM Audit

| Field | Value |
|-------|--------|
| **Artifact** | `03_core_platform.md` |
| **Scope** | Orchestrator, Governor, Session, Model, Interface, Engine SPI, Observability, Resource |
| **Docs authority** | `...\OmniLLM_Product_Documents\docs\30-core-platform\*` + `specs/*` (specs win on conflict) |
| **Code under audit** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Audit date** | 2026-08-12 |
| **Method** | `list_dir` / `read_file` / `grep` on real paths — fail-closed (no path evidence ⇒ not PASS) |
| **Status labels** | PASS \| PARTIAL \| MISSING \| N_A \| BLOCKED_HUMAN only |
| **Layer legend** | **L1** module exists · **L2** wired to control plane · **L3** product journey software-complete |

> **Hard rules applied:** engine QUALIFIED/SUPPORTED only with repo evidence matching docs; no invented device/Play/OEM PASS; claims require path + symbol/quote.

---

## 0) Executive summary

| Area | Doc ID | L1 | L2 | L3 software | Overall |
|------|--------|----|----|-------------|---------|
| Orchestrator + scheduler | CORE-ORCHESTRATOR | **PASS** | **PARTIAL** | **PARTIAL** | **PARTIAL** |
| Resource governor | CORE-RESOURCE | **PASS** | **PARTIAL** | **PARTIAL** | **PARTIAL** |
| Session / context / KV | CORE-SESSION | **PASS** | **PASS** | **PARTIAL** | **PARTIAL** |
| Engine SPI + registry | CORE-ENGINE | **PASS** | **PARTIAL** | **PARTIAL** | **PARTIAL** |
| Model platform | CORE-MODEL | **PASS** | **PASS** | **PARTIAL** | **PARTIAL** |
| Interface platform | CORE-INTERFACE | **PASS** | **PASS** | **PARTIAL** | **PARTIAL** |
| Observability | CORE-OBSERVABILITY | **PASS** | **PARTIAL** | **PARTIAL** | **PARTIAL** |
| Capability / fail-closed | CORE-CAPABILITY | **PASS** | **PASS** | **PARTIAL** | **PARTIAL** |
| Formal contracts | CORE-CONTRACT-ARTIFACTS | **PASS** | **PASS** | **PARTIAL** | **PARTIAL** |

**Headline:** Core control-plane modules are **software-present (L1)** and largely **wired into `RuntimeControlPlane` (L2)**. Normative shapes for plan purity, Reservation vs Allocation, fail-closed UNKNOWN capability, and event kinds exist with unit tests. Production gaps that prevent full L3 software-complete: **Orchestrator does not inject durable `CommitLedger`**, governor ledger is **in-memory only**, health/thermal lookup is **stub**, engine cells remain **UNKNOWN without device evidence** (correct fail-closed), embeddings remain fail-closed UNKNOWN on AIDL path, and **planHint/commitHint** SPI is absent.

---

## 1) Authority map (docs + specs)

### 1.1 Docs package (`docs/30-core-platform/`)

| Doc ID | File | Authority |
|--------|------|-----------|
| CORE-ORCHESTRATOR | `orchestrator-scheduler.md` | NORMATIVE |
| CORE-RESOURCE | `resource-governance.md` | NORMATIVE |
| CORE-SESSION | `session-context-kv.md` | NORMATIVE |
| CORE-ENGINE | `engine-platform.md` | NORMATIVE |
| CORE-MODEL | `model-platform.md` | NORMATIVE |
| CORE-INTERFACE | `interface-platform.md` | NORMATIVE |
| CORE-OBSERVABILITY | `observability-diagnostics.md` | NORMATIVE |
| CORE-CAPABILITY | `capability-compatibility.md` | NORMATIVE |
| CORE-CONTRACT-ARTIFACTS | `formal-contract-artifacts.md` | NORMATIVE |

### 1.2 Specs precedence (used when prose conflicts)

| Spec | Role |
|------|------|
| `specs/canonical-types.yaml` | ResourceVector dimensions, ResourceEnvelope steady/peak, OperationContext, FallbackPolicy, CapabilityState |
| `specs/state-machines.yaml` | REQUEST, RESERVATION, ALLOCATION, SESSION, LOADED_MODEL, MODEL_INSTALLATION, OPERATION, COMMIT |
| `specs/observability-catalog.yaml` | Metric IDs / privacy / aggregation |
| `specs/openapi/omnillm.openapi.yaml` | HTTP surface |
| `specs/aidl/omnillm-aidl.yaml` | AIDL semantic IDL |
| `specs/engine-qualification-schema.yaml` | defaultRuntimeCapability = UNKNOWN |

**Prose vs specs note:** CORE-RESOURCE prose lists `cpuPeakBytes` as a ResourceVector dimension. **Specs** state ResourceVector has **no embedded peak field**; peak is `ResourceEnvelope.peak` (each dimension ≥ steady). Implementation matches **specs** — not prose. Status for that dimension: **N_A** (specs authority).

---

## 2) Module inventory (L1)

### 2.1 Runtime

| Gradle / path | Present | Primary symbols |
|---------------|---------|-----------------|
| `:runtime:orchestrator` | **PASS** | `Orchestrator`, `CandidatePlanner`, `DeficitRoundRobinScheduler`, `RequestLifecycle` |
| `:runtime:governor` | **PASS** | `ResourceGovernor`, `ResourceLedger` |
| `:runtime:session` | **PASS** | `SessionManager`, `InMemorySessionManager`, `DurableSessionManager` |
| `:runtime:model-manager` | **PASS** | `ModelManager`, `LoadCoordinator`, `InstallationCoordinator` |
| `:runtime:request-registry` | **PASS** | `RequestRegistry`, `CommitLedger`, `CommandLedger` |
| `:runtime:observability` | **PASS** | `ObservabilityFacade`, `MetricCatalog`, `HealthRegistry`, `RequestTrace` |
| `:runtime:job-manager` | **PASS** | (adjacent; admin/jobs) |
| `:runtime:policy` | **PASS** | `ConfigurationCatalog.governorCapacities` |

### 2.2 Core + engines SPI + interfaces

| Path | Present | Notes |
|------|---------|-------|
| `core/resource` | **PASS** | `Reservation`, `AllocationHandle`, `Conservation`, `OperatingConstraint` |
| `core/canonical` | **PASS** | Generated `ResourceVector`, `CapabilityState`, `OmniResult` |
| `core/contracts` | **PASS** | `Plan`, `Commit`, `PreparedOperation` |
| `core/state` | **PASS** | Generated `StateMachines`, `StateMachineDriver` |
| `engines/api` | **PASS** | `OmniEngine`, `LoadedModelPort`, `EngineRegistry`, `EngineEvent` |
| `interfaces/http` | **PASS** | `OmniHttpRoutes`, OpenAPI paths |
| `interfaces/aidl` | **PASS** | 44 `.aidl` sources including `IOmniRuntime`, `IStreamSession` |
| `interfaces/admin` | **PASS** | `AdminApiService` |

---

## 3) Focus checks (task-mandated)

### 3.1 Plan purity (ADR-002 / CORE-ENGINE §3 / CORE-ORCHESTRATOR §2)

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Engine SPI declares plan = no domain mutation | **PASS** | `engines/api/.../OmniEngine.kt`: “Plan has no domain mutation (ADR-002)”; `planProbe` / `planLoad` pure; `LoadedModelPort.planInference` “no KV mutation” |
| Load plan pure | **PASS** | `LoadContracts.kt` `EngineLoadPort.planLoad`; `LoadCoordinator.planLoad` does not create LoadedModel rows |
| Orchestrator planning pure | **PASS** | `CandidatePlanner` “Does **not** mutate domain state”; calls `engine.planInference` only after capability filters |
| Control-plane Plan type | **PASS** | `core/contracts/PlanCommitContracts.kt` `Plan` doc: “Plan has **no** domain mutation” |
| Unit tests | **PASS** | `engines/api/.../FakeEnginePipelineTest.kt` Plan→Reserve→Commit→Execute; orchestrator planner tests |
| planHint → reserve → commitHint (CORE-ENGINE §8) | **MISSING** | Grep over monorepo `*.kt` for `planHint`/`commitHint` SPI: **no engine API matches** (only UI/catalog “hint” strings) |

**Section status: PARTIAL** — core PRCE plan purity **PASS**; dynamic workspace hints SPI **MISSING**.

### 3.2 Reservation vs Allocation (CORE-RESOURCE §4 / ADR-003)

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Distinct types | **PASS** | `core/resource/ReservationAndAllocation.kt`: `Reservation` (temporary peak) vs `AllocationHandle` (resident after convert) |
| Conservation identity | **PASS** | `Conservation.splitConversion` / `checkConversion`: `beforeReserved = converted + remainder`; tests in `ResourceConservationTest` |
| Governor reserve | **PASS** | `ResourceGovernor.reserve` charges peak; records `OperatingConstraint` **outside** vector ledger |
| Governor convert | **PASS** | `ResourceGovernor.convert` uses RESERVATION FSM + conservation proof; idempotent by reservation id |
| Release residual ≠ free allocation | **PASS** | `releaseReservation` does not credit `AllocationHandle`; comments INV-005 |
| Eviction barrier | **PASS** | `requestDrain` → `markOwnerQuiescent` → `completeReleaseBarrier(nativeBarrierObserved=true)` only credits free |
| Orchestrator convert after commit | **PASS** | `Orchestrator.executeWork`: after COMMIT_CONFIRMED, `governor.convert(... steady ...)` |
| OperatingConstraint separation | **PASS** | `OperatingConstraint.kt`; ResourceVector DIMENSION_NAMES exclude thermal/power; unit tests assert separation |
| Specs ResourceVector dimensions | **PASS** | Generated `ResourceVector` matches `canonical-types.yaml` (10 additive dims; peak via envelope) |
| Durable governor ledger across process death | **MISSING** | `ResourceGovernor` is in-memory (`linkedMapOf` reservations/allocations); no SQL ledger ports found under governor |
| App/system/accelerator budget composition (CORE-RESOURCE §3) | **PARTIAL** | `ConfigurationCatalog.governorCapacities` supplies fixed/versioned caps; **no** dynamic system headroom / accelerator self-report / PSS-USS charge policy implementation grepped under governor/resource |

**Section status: PARTIAL** — algorithm and types strong; process-durable ledger and full budget composition incomplete.

### 3.3 Fail-closed unknown capability (CORE-CAPABILITY / INV-018)

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Catalog-unknown capability ID rejected | **PASS** | `UnknownCapabilityNegativeTest.catalogUnknownCapabilityId_failClosed`; `CapabilityId.requireFromId` |
| Evidence UNKNOWN rejects candidate | **PASS** | `CandidatePlanner` filter 1: `CapabilityState.UNKNOWN` → rejection “fail closed”; test `evidenceUnknown_capabilityRejected_noViableCandidate` asserts `planCount==0` |
| Registry default UNKNOWN | **PASS** | `EngineRegistry.resolveCapability` missing cell → UNKNOWN; `projectRuntimeCapability` only `QUALIFIED_WITH_ENVELOPE` + evidence PASS → SUPPORTED |
| Control plane documents fail-closed | **PASS** | `RuntimeControlPlane` KDoc: “Unknown capabilities and unattached engines fail closed”; engine attach “Never elevates … SUPPORTED without evidence” |
| AIDL embedding fail-closed | **PASS** | `OmniRuntimeFacade.embed` terminal `CAPABILITY_UNKNOWN` — “no silent elevate” |
| Do not invent QUALIFIED | **PASS** (audit rule) | Registry cells may exist as UNQUALIFIED/UNKNOWN; no device PASS matrix claimed in this audit |

**Section status: PASS** (software fail-closed path). Product journeys that need SUPPORTED remain **BLOCKED_HUMAN** for real device qualification evidence (out of this report’s invention scope).

### 3.4 Event contracts (CORE-ENGINE §6 / CORE-INTERFACE §12)

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Engine event kinds | **PASS** | `EngineEventKinds`: metadata, delta, usage, diagnostic, warning, terminal; unknown kind fails closed in `EngineEvent` init |
| Half-open sequence / epoch | **PASS** | `EngineEvents.kt` KDoc; AIDL ACK `seqToExclusive`; `CommitCheckpoint` / `DeliverySemantics.AidlApplicationAck` |
| Terminal unique (documented) | **PASS** | `LoadedModelPort.start` KDoc; FakeEngine streams terminal once |
| AIDL credit/ACK delivery | **PARTIAL** | `StreamSessionFacade.grantCredit` + `ack` present in runtime-service Kotlin; formal `IStreamSession.aidl` has `ackEvents`, `cancel`, `query`, `close` — **no** `grantCredit` on wire AIDL (shim note in facade KDoc) |
| SSE pre-stream vs post-stream errors | **PASS** | `OmniHttpRoutes` KDoc CORE-INTERFACE §4; routes structure present |
| GenerationEvent tool-proposal union full product journey | **PARTIAL** | Tool proposal ledgers exist under control plane / features; full sealed GenerationEvent projection across all transports not fully proven as L3 in this pass |

**Section status: PARTIAL**.

---

## 4) Subsystem deep dive

### 4.1 Orchestrator + Request Registry + Scheduler

**Docs:** CORE-ORCHESTRATOR §1–8.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| REQUEST pipeline FSM | **PASS** | `RequestLifecycle` + `StateMachines.REQUEST`; illegal edges fail closed (`RequestLifecycleDriverTest`) |
| Claim `(principal, operation, idempotencyKey)` + hash | **PASS** | `RequestRegistry.claim`; conflict → `IDEMPOTENCY_CONFLICT` |
| Candidate filter order | **PASS** | `CandidatePlanner` filters 1–8 as documented (capability → fallback → placement → backend → session → plan envelope → health/revocation) |
| Rejection trail retained | **PASS** | `CandidateRejection` + `PlanningResult.rejections` |
| Fallback only with allowlist | **PASS** | `FallbackPolicy.NONE / SAME_REVISION_ONLY / ALLOW_LIST`; `ActualRouting.usedFallback` |
| Per-principal FIFO + global DRR | **PASS** | `DeficitRoundRobinScheduler` |
| Cancel / deadline pre-check | **PASS** | `Orchestrator.submit` cancel + deadline; phase cancel depth varies by engine |
| INTENT_RECORDED before worker commit (design) | **PARTIAL** | Code path exists (`commitLedger?.recordIntent`) but **production wiring leaves `commitLedger=null`** |
| Production Orchestrator injects durable CommitLedger | **MISSING** | `OrchestratorModule.create` does **not** accept/pass `commitLedger`; `WaveAWiring.wire` calls that factory; plane holds separate `commitLedger` not bound to Orchestrator |
| Health/thermal from real sensors | **PARTIAL** | Production uses `HealthLookup { HealthSnapshot() }` defaults — not live thermal/engine health |
| L2 control-plane wiring | **PARTIAL** | Orchestrator created in Wave-A wiring, held on `RuntimeControlPlane.orchestrator`; AIDL chat uses orchestrated path (`OmniRuntimeFacade.startOrchestratedChat`) |
| L3 product journey | **PARTIAL** | Software path exists with exploratory/unqualified engines; full qualified generation journey needs engine evidence |

**Orchestrator overall: PARTIAL**

#### Critical wiring gap (recovery)

```text
RuntimeControlPlane.attach
  → durable CommitLedger (SQL) on plane
  → WaveAWiring.wire → OrchestratorModule.create(... no commitLedger ...)
  → Orchestrator(commitLedger = null)
  → recordIntent / claimStart are no-ops at runtime
```

Evidence paths:

- `runtime/orchestrator/.../Orchestrator.kt` constructor default `commitLedger: CommitLedger? = null`
- `runtime/orchestrator/.../OrchestratorModule.kt` `create(...)` — no commitLedger parameter
- `android/runtime-service/.../WaveAWiring.kt` lines ~213–221
- Plane still stores `commitLedger` for recovery helpers (`RuntimeControlPlane.kt`)

### 4.2 Resource Governor

**Docs:** CORE-RESOURCE.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Multi-dim ResourceVector + envelope | **PASS** | Generated vector + `ResourceEnvelope` steady/peak |
| Checked arithmetic fail-closed | **PASS** | `Math.addExact` / `ResourceArithmetic.tryPlus`; overflow tests |
| Reservation / Allocation / barrier | **PASS** | §3.2 above |
| Catalog RESERVATION/ALLOCATION FSM | **PASS** | `StateMachineDriver` + `StateMachines.RESERVATION/ALLOCATION` |
| Capacities from policy catalog | **PASS** | `ConfigurationCatalog.governorCapacities`; WaveAWiring constructs governor |
| Memory observation (PSS/USS/RSS policy) | **MISSING** | No governor implementation of charge observation policy grepped |
| Process-durable allocation handles | **MISSING** | In-memory only |
| L2 wired | **PASS** | `RuntimeControlPlane.resourceGovernor` from Wave-A |
| L3 | **PARTIAL** | Admission works in software tests; not device-calibrated |

**Resource overall: PARTIAL**

### 4.3 Session / Context / KV

**Docs:** CORE-SESSION.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| SessionDescriptor min fields | **PASS** | `SessionDescriptor.kt` fields match §1 (sessionId, epoch, ownerKey, revision, loadKey, templateEpoch via loadKey, tokenizerDigest, contextConfig, fingerprint, state, allocationHandleId) |
| SourceSessionRef None / Existing | **PASS** | `SourceSessionRef` + engine SPI `EngineSourceSessionRef` — no magic epoch −1 |
| Prefix decision types | **PASS** | Catalog `PrefixDecision`; `PrefixDecisionRecord`; plan stores proposed, commit executes |
| Commit checkpoints + AIDL ACK only for ASSISTANT_ACKNOWLEDGED | **PASS** | `CommitCheckpoint`, `DeliverySemantics`, tests reject SSE advancing ACK |
| SESSION FSM + POISONED never re-pool | **PASS** | `SessionPoolPolicy.NEVER_POOL_STATES`; INV-007 comments + tests |
| Durable control-plane sessions | **PASS** | `DurableSessionManager` + SQL ports; rehydrate non-CLOSED; **explicit non-claim**: native KV not restored |
| Pool partition owner+loadKey | **PASS** | `SessionPoolKey` / pool policy |
| Native KV durable snapshot protocol | **MISSING** | Documented non-claim in DurableSessionManager — correct honesty, product incomplete |
| L2 | **PASS** | `SessionModule.createDurableManager` in control plane attach; drain on runtime stop |
| L3 | **PARTIAL** | Control-plane session software-complete; native session reuse incomplete |

**Session overall: PARTIAL**

### 4.4 Engine SPI + Registry

**Docs:** CORE-ENGINE, CORE-CAPABILITY, ENGINE-QUALIFICATION.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| OmniEngine / LoadedModelPort shape | **PASS** | Matches docs §2 (describe, planProbe, probe, planLoad, commitLoad, queryCommit, plan/commit/start inference, embedding, close, unload) |
| Plan → Reserve → Commit → Execute | **PASS** | SPI + FakeEngine + Orchestrator |
| OmniResult error contract | **PASS** | Interfaces return `OmniResult` |
| Event contract | **PASS** | `EngineEvent` / kinds |
| Qualification projection fail-closed | **PASS** | `EngineRegistry.projectRuntimeCapability` |
| Control-plane attach all catalog engines | **PASS** | `EnginePackAttachment` + `ensureEnginePacksAttached` |
| Only QUALIFIED+PASS as SUPPORTED | **PASS** | Registry rules; exploratory path CONDITIONAL under flag — not SUPPORTED invent |
| planHint/commitHint | **MISSING** | §3.1 |
| Engine packs QUALIFIED for devices | **BLOCKED_HUMAN** | Requires device evidence; not claimed PASS |

**Engine SPI overall: PARTIAL**

### 4.5 Model platform

**Docs:** CORE-MODEL.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Separate Installation vs LoadedModel aggregates | **PASS** | `ModelManager` + `InstallationCoordinator` + `LoadCoordinator`; comments CORE-MODEL §1 |
| Installation lifecycle READY ≠ loaded | **PASS** | InstallationCoordinator states; LoadCoordinator requires READY for plan |
| Privileged load re-verify hook | **PARTIAL** | `DefaultPrivilegedLoadReverify` / `PrivilegedLoadGate`; attach uses `failClosedUntilSupplyWired` until supply wired fully |
| RevisionLease for delete fencing | **PASS** | `RevisionLeaseService` + durable SQL |
| Durable SQL + filesystem model store | **PASS** | Control plane opens SQLDelight + `ModelStoreModule.createFilesystemPort` |
| Signed catalog / pin download / local import journeys | **PARTIAL** | Feature packs (modelhub/autosetup) — software present; full supply-chain product readiness out of pure core SPI |

**Model overall: PARTIAL** (core manager L1/L2 strong)

### 4.6 Interface platform

**Docs:** CORE-INTERFACE.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| HTTP routes align OpenAPI path set | **PASS** | `OmniHttpRoutes` + `OpenApiPaths` (health, models, chat, assets, admin, metrics, …) |
| Loopback token auth (except health) | **PASS** | Route authenticator pattern |
| AIDL IOmniRuntime chat/embed/query/cancel | **PASS** | `IOmniRuntime.aidl` |
| Principal not self-reported package | **PASS** | `PrincipalObservation`, `ClientRegistrationStore` |
| AssetHandle broker (HTTP+AIDL) | **PASS** | `AssetHandleBroker` on plane; HTTP asset lifecycle routes |
| AIDL grantCredit on formal wire | **PARTIAL** | Kotlin credit window; AIDL interface omits grantCredit |
| Admin CommandResult non-void | **PASS** | `AdminApiService` / AIDL `CommandResult` |
| L2 wiring | **PASS** | Facades → control plane; `ControlPlaneHttpHandler` |
| L3 all formal surfaces product-complete | **PARTIAL** | Embedding UNKNOWN; some admin/metrics projections restricted |

**Interface overall: PARTIAL**

### 4.7 Observability

**Docs:** CORE-OBSERVABILITY.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Metric catalog IDs (TTFT, tokens/s, queue, errors, resource) | **PASS** | `MetricCatalog` / `MetricId.REQUEST_TTFT_MS` etc. |
| Health levels HEALTHY/DEGRADED/UNAVAILABLE/UNKNOWN | **PASS** | `HealthLevel` + subject kinds including RESOURCE_GOVERNOR |
| Trace redaction (no prompt/path) | **PASS** | `RequestTrace` + `Redactor` + forbidden dimension keys |
| Evidence labels | **PASS** | `EvidenceSemantics` + tests |
| L2 facade on plane | **PASS** | `ObservabilityModule.createFacade()` in attach |
| Continuous operational collection on all request phases | **PARTIAL** | Facade exists; not fully proven that every orchestrator phase records TTFT/throughput in production path |
| Dashboard decision support L3 | **PARTIAL** | Feature dashboard consumes ports; full measured vs estimated vs stale UX is feature-scope |

**Observability overall: PARTIAL**

### 4.8 Formal contract artifacts

**Docs:** CORE-CONTRACT-ARTIFACTS.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Generated Kotlin from specs | **PASS** | `core/canonical/generated/*`, `core/state/generated/StateMachines.kt` headers “GENERATED … specs” |
| Monorepo `specs/` present | **PASS** | `omnillm-android/specs/*` |
| AIDL compiled artifacts | **PASS** | `interfaces/aidl` generates Java stubs |
| Drift gates / golden vectors | **PARTIAL** | Tests (`GoldenIdentityEncodingTest`, FSM fixtures) present; continuous CI gate not re-run in this audit |

**Contracts overall: PARTIAL** (design+codegen present; full multi-language gate evidence not claimed)

---

## 5) L2 control-plane wiring map

| Component | Wired in `RuntimeControlPlane.attach`? | Evidence |
|-----------|----------------------------------------|----------|
| RequestRegistry (SQL claims) | **YES** | `RequestRegistryModule.createWithCommits` |
| CommitLedger (SQL) | **YES on plane** / **NO on Orchestrator** | plane field vs Orchestrator null |
| SessionManager durable | **YES** | `SessionModule.createDurableManager` |
| JobManager durable | **YES** | `JobManagerModule.createDurableManager` |
| ModelManager durable | **YES** | `ModelManagerModule.createDurableControlPlane` |
| ResourceGovernor | **YES** | WaveAWiring → plane.resourceGovernor |
| Orchestrator | **YES** | WaveAWiring → plane.orchestrator |
| ObservabilityFacade | **YES** | ObservabilityModule |
| EngineRegistry / packs | **YES (after READY)** | `ensureEnginePacksAttached` |
| HTTP gateway | **YES** | GatewayLifecycle + ControlPlaneHttpHandler |
| AIDL facades | **YES** | OmniRuntimeFacade / Admin / Binding |
| Feature packs Wave-A/B | **YES** | FeaturePackHost.bootstrap |

**L2 summary: PARTIAL** — core modules attached, but **intent ledger not connected to Orchestrator** and health stubbed.

---

## 6) L3 product-journey software completeness (core only)

| Journey | Software status | Notes |
|---------|-----------------|-------|
| Claim → plan → queue → reserve → commit → stream → terminal (happy path code) | **PARTIAL** | Pipeline implemented; durable commit intent optional/null in prod wiring |
| Fail-closed unknown capability | **PASS** | Unit + AIDL embed path |
| Fallback disclosure | **PASS** | ActualRouting |
| Session pool / poison | **PARTIAL** | Control-plane complete; native KV not durable |
| Load READY installation via planLoad/commitLoad | **PARTIAL** | Coordinators + privileged gate; exploratory/fixture paths |
| Embeddings first-class | **PARTIAL** | SPI present; product AIDL path fail-closed UNKNOWN (honest) |
| Metrics/TTFT for ops | **PARTIAL** | Catalog+registry; production emission completeness unproven here |

No device PASS, Play upload, or OEM matrix results are claimed.

---

## 7) Findings register

| ID | Severity | Status | Finding | Evidence |
|----|----------|--------|---------|----------|
| CP-01 | **High** | **MISSING** (wiring) | Production Orchestrator never receives durable `CommitLedger`; INTENT_RECORDED / start claim durability is no-op | `OrchestratorModule.create`, `WaveAWiring.wire`, `Orchestrator` default null |
| CP-02 | Medium | **MISSING** | ResourceGovernor reservation/allocation ledger not process-durable | `ResourceGovernor` in-memory maps only |
| CP-03 | Medium | **PARTIAL** | HealthLookup / thermal always default snapshot in Wave-A wiring | `WaveAWiring` `HealthLookup { HealthSnapshot() }` |
| CP-04 | Medium | **MISSING** | CORE-ENGINE §8 planHint/commitHint SPI not implemented | Grep: no `planHint`/`commitHint` in engines/api |
| CP-05 | Low | **PARTIAL** | AIDL formal `IStreamSession` lacks `grantCredit`; credit is Kotlin-side shim | `IStreamSession.aidl` vs `StreamSessionFacade.grantCredit` |
| CP-06 | Low | **N_A** | Prose `cpuPeakBytes` vs specs envelope peak — impl correctly follows specs | `canonical-types.yaml` ResourceVector; generated `ResourceVector.kt` |
| CP-07 | Medium | **MISSING** | Memory observation charge policy (PSS/USS/RSS roles, isolated self-report) | CORE-RESOURCE §7; no implementation under governor |
| CP-08 | Info | **PASS** | Fail-closed UNKNOWN capability + registry projection | `CandidatePlanner`, `EngineRegistry`, tests |
| CP-09 | Info | **PASS** | Reservation vs Allocation conservation + barrier | `Conservation`, `ResourceGovernor`, tests |
| CP-10 | Info | **PASS** | Plan purity on SPI + planner + load coordinator | OmniEngine / CandidatePlanner / LoadCoordinator |
| CP-11 | Low | **PARTIAL** | Durable sessions do not restore native KV (documented non-claim) | `DurableSessionManager` KDoc |
| CP-12 | — | **BLOCKED_HUMAN** | Engine QUALIFIED/SUPPORTED on real devices | Qualification evidence not inventable |

---

## 8) Positive conformance (keep)

1. **PRCE discipline** is real in code and tests (FakeEngine pipeline, Orchestrator pump).
2. **Governor conservation** and non-crediting planned eviction match ADR-003.
3. **Capability fail-closed** is enforced at catalog ID, candidate filter, and registry projection.
4. **Session delivery semantics** correctly separate AIDL application ACK from SSE write completion.
5. **Two-aggregate model** (Installation vs LoadedModel) is structural, not a single fake state.
6. **Control plane sole-writer attach** enforces runtime process identity (INV-001 / ADR-010).

---

## 9) Recommended close-out order (audit only — not implemented here)

1. **CP-01:** Thread plane `CommitLedger` into `OrchestratorModule.create` / WaveAWiring (required for REL-RECOVERY claim).
2. **CP-03:** Wire `HealthLookup` to Observability `HealthRegistry` + thermal policy snapshots.
3. **CP-02 / CP-07:** Durable allocation handles + memory charge observation (or explicit product waiver).
4. **CP-04:** SPI for planHint/commitHint when engines need dynamic workspace growth.
5. **CP-05:** Reconcile AIDL semantic IDL vs Kotlin credit API.
6. Keep **UNKNOWN** default until real qualification cells exist (**CP-12**).

---

## 10) Search / read log (empty-findings accountability)

| Action | Path / pattern |
|--------|----------------|
| list_dir | docs `30-core-platform`, monorepo `runtime/*`, `engines/api`, `core/*`, `interfaces/*`, `android/runtime-service` |
| read_file | All 9 CORE-* docs; Orchestrator, CandidatePlanner, ResourceGovernor, OmniEngine, LoadedModelPort, EngineEvents, EngineRegistry, Session*, RequestRegistry, ModelManager, LoadCoordinator, RuntimeControlPlane, WaveAWiring, OmniRuntimeFacade, ObservabilityFacade, MetricCatalog, PlanCommitContracts, Conservation, ReservationAndAllocation, OperatingConstraint, ResourceVector, IStreamSession.aidl, FakeEngine |
| grep | `cpuPeakBytes`, `planHint`/`commitHint`, `UNKNOWN`/`fail closed`, `commitLedger`, `PSS|USS|RSS`, `grantCredit`, Orchestrator/governor wiring |
| specs | `canonical-types.yaml` ResourceVector/Envelope; monorepo `state-machines.yaml` REQUEST |

---

## 11) Overall CORE PLATFORM verdict

| Verdict field | Value |
|---------------|--------|
| **Overall status** | **PARTIAL** |
| **L1 modules** | **PASS** (all core runtime/core/engine-api/interface modules present) |
| **L2 control-plane wiring** | **PARTIAL** (attached; commitLedger→Orchestrator gap; health stub) |
| **L3 product software-complete** | **PARTIAL** (pipeline + fail-closed software; durable recovery/intent path incomplete; native KV/qualification incomplete) |
| **Plan purity** | **PARTIAL** (core PRCE PASS; hints MISSING) |
| **Reservation vs Allocation** | **PARTIAL** (types/algorithm PASS; durable ledger MISSING) |
| **Fail-closed unknown capability** | **PASS** |
| **Event contracts** | **PARTIAL** |

---

*End of `03_core_platform.md`. Auditor: independent software audit against 新版本 docs package; no device/Play/OEM results invented.*
