# Feature Pack Host (android/runtime-service/featurehost)

Process-local wiring of Feature Packs inside the runtime control plane
(`:android:runtime-service`). This is the **only** place Feature Pack APIs are
constructed with control-plane ports (ADR-010 single writer lives here).

## Wave-A vs Wave-B (dual wiring — ARC-04)

The host wires Feature Packs in two groups:

| Wave | Feature Packs | Wired by | Dependencies |
|---|---|---|---|
| Wave-A | admin, auto-setup, modelhub, playground, server, dashboard | `WaveAWiring.wire(...)` (RuntimeControlPlane attach) | Orchestrator, ResourceGovernor, EngineExecuteBinding, ModelManager, RequestRegistry |
| Wave-B | lan, benchmark, diagnostics, routing, tools, ai-content-report | `FeaturePackHost.bootstrap(...)` | JobManager, PolicyManager, Observability; orchestrator when Wave-A present |

**Ordering constraint (why Wave-A first):** `FeaturePackHost.bootstrap` receives
an already-wired `WaveAFeaturePacks` and derives wave-B ports from it
(`routingOrchestrator`, `toolsInference`, playground/server inference). Wave-B
APIs therefore cannot exist when Wave-A is constructed — they are created
*after* `WaveAWiring.wire()` returns.

**Cross-wiring cycle (why the late-bind holders exist):** two Wave-A adapters
consume Wave-B APIs that are created later:

- `ToolsApiHolder` → `ToolsApiPlaygroundStructuredAdapter` (playground
  STRUCTURED_TOOLS tab reads `ToolsApi` lazily).
- `BenchmarkApiHolder` → `DeferredDashboardMeasurementPort` (dashboard
  MEASUREMENTS last-run projection reads `BenchmarkApi` lazily).

Because `toolsApi`/`benchmarkApi` construction requires the Wave-A orchestrator,
true constructor injection at Wave-A bootstrap time is a cycle; the holders are
the documented late-bind seam. They are `@Volatile` and fail closed (null ⇒
empty projection) — no unset holder can crash a read. Removing the pattern
requires reordering bootstrap (create Wave-B before Wave-A, which currently
needs Wave-A ports) and is tracked debt (ARC-04; see the KDoc on the holders).

## Invariants enforced here

- `FeaturePackHost.bootstrap` asserts wave-B IDs are always present and that
  wave-A IDs appear exactly when `waveA != null`.
- Engine ports route through `EngineExecuteBinding` (honest capability lookup);
  permanent `FailClosed*` ports are used only when the engine is unattached.
- Synthetic identities are never fabricated: durable request paths resolve the
  real installation via `ControlPlaneFeaturePorts.resolveInstallationOrNull`
  and fail closed with `CAPABILITY_UNSUPPORTED` when absent (ARC-06).
