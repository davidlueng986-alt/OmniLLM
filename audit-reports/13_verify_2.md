# Adversarial verify — Claim #2

**Claim:** All 12 Feature Packs are hosted on `RuntimeControlPlane`  
**Auditor role:** Independent (re-inspected docs package + monorepo; prior audits not used as sole authority)  
**Date:** 2026-08-12  
**Scope of claim (interpreted):** L2 — each of the 12 product Feature Packs is **constructed and retained** on the production `:runtime` control plane (`RuntimeControlPlane.attach` → `WaveAWiring` + `FeaturePackHost.bootstrap`). This is **not** an L3 claim (device journeys, Play upload, OEM matrix).

---

## Verdict

```yaml
claim_id: 2
claim: "All 12 Feature Packs are hosted on RuntimeControlPlane"
real: true
status: PASS
level: L2_HOSTING
reason: >
  Docs define exactly 12 FEAT-* packs (FEATURE-SYSTEM is design system, not a pack).
  Monorepo includes exactly 12 :features:* modules. Production RuntimeControlPlane.attach
  always wires Wave-A (6) via WaveAWiring.wire then FeaturePackHost.bootstrap(waveA=…),
  which constructs Wave-B (6) and registers featureIds for all 12 with runtime checks.
  Plane exposes typed accessors for every pack; Gradle runtime-service depends on all 12 modules.
  Unit tests cover bootstrap path with both waves present.
```

---

## Canonical 12 Feature Packs (docs authority)

**Source (prefer machine-readable + feature catalog):**

| # | Feature ID | Docs path | Gradle module |
|---|------------|-----------|---------------|
| 1 | `FEAT-ADMIN` | `docs/70-features/administration-jobs.md` | `:features:admin` |
| 2 | `FEAT-AI-CONTENT-REPORT` | `docs/70-features/ai-content-reporting.md` | `:features:ai-content-report` |
| 3 | `FEAT-AUTOSETUP` | `docs/70-features/auto-setup.md` | `:features:auto-setup` |
| 4 | `FEAT-BENCHMARK` | `docs/70-features/benchmark-research.md` | `:features:benchmark` |
| 5 | `FEAT-DASHBOARD` | `docs/70-features/dashboard-monitoring.md` | `:features:dashboard` |
| 6 | `FEAT-SERVER` | `docs/70-features/developer-server.md` | `:features:server` |
| 7 | `FEAT-DIAGNOSTICS` | `docs/70-features/diagnostics-export.md` | `:features:diagnostics` |
| 8 | `FEAT-LAN` | `docs/70-features/lan-access.md` | `:features:lan` |
| 9 | `FEAT-PLAYGROUND` | `docs/70-features/local-playground.md` | `:features:playground` |
| 10 | `FEAT-MODELHUB` | `docs/70-features/modelhub-acquisition.md` | `:features:modelhub` |
| 11 | `FEAT-ROUTING` | `docs/70-features/multi-model-routing.md` | `:features:routing` |
| 12 | `FEAT-TOOLS` | `docs/70-features/structured-tools.md` | `:features:tools` |

**Docs evidence:**

- `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\docs\70-features\README.md` — table lists the 12 `FEAT-*` IDs above (plus `FEATURE-SYSTEM` design system, **not** a pack).
- Product `specs/feature-capability-map.yaml` lists 12 `featureId` entries (alias note: product map uses `FEAT-AI-REPORTING`; monorepo `specs/feature-capability-map.yaml` and code use `FEAT-AI-CONTENT-REPORT` — same pack).
- Monorepo `settings.gradle.kts` `include(` features block: exactly 12 feature modules.

---

## Evidence (concrete paths + symbols)

### E1 — Production attach hosts Wave-A then Wave-B

**File:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\controlplane\RuntimeControlPlane.kt`

- KDoc (class header): Wave-A = admin, auto-setup, modelhub, playground, server, dashboard; Wave-B = lan, benchmark, diagnostics, routing, tools, content-report.
- `attach(context)` asserts `ProcessIdentity.isRuntimeProcess()` + `SingleWriterPolicy.assertWriterAllowed` (INV-001 / ADR-010).
- Production path:
  - `WaveAWiring.wire(WaveAWiring.Deps(...))` → constructs all 6 Wave-A APIs.
  - `FeaturePackHost.bootstrap(..., waveA = waveA, ...)` → constructs all 6 Wave-B APIs and embeds Wave-A.
  - `RuntimeControlPlane(..., featurePacks = featurePacks, orchestrator = waveA.orchestrator, ...)` retains host on plane.
  - Log line: `featurePacks=${featurePacks.featureIds.joinToString(",")}` at attach.

### E2 — Typed accessors for all 12 packs on the plane

Same file — convenience properties:

| Accessor | Pack |
|----------|------|
| `adminFeatureApi` | FEAT-ADMIN |
| `autoSetupApi` | FEAT-AUTOSETUP |
| `modelHubApi` | FEAT-MODELHUB |
| `playgroundApi` | FEAT-PLAYGROUND |
| `developerServerApi` | FEAT-SERVER |
| `dashboardApi` | FEAT-DASHBOARD |
| `lanApi` | FEAT-LAN |
| `benchmarkApi` | FEAT-BENCHMARK |
| `diagnosticsApi` | FEAT-DIAGNOSTICS |
| `routingApi` | FEAT-ROUTING |
| `toolsApi` | FEAT-TOOLS |
| `contentReportApi` | FEAT-AI-CONTENT-REPORT |

Wave-A accessors require `featurePacks.waveA` non-null (`error("Wave-A Feature Packs not attached...")`). Production bootstrap always injects `waveA`.

### E3 — FeaturePackHost registers 6+6 IDs and fail-checks completeness

**File:** `...\featurehost\FeaturePackHost.kt`

- `WAVE_B_FEATURE_IDS` = `{FEAT-LAN, FEAT-BENCHMARK, FEAT-DIAGNOSTICS, FEAT-ROUTING, FEAT-TOOLS, FEAT-AI-CONTENT-REPORT}`
- `featureIds` adds Wave-B module IDs + `WaveAWiring.WAVE_A_FEATURE_IDS` when `waveA != null`
- `bootstrap` ends with:
  - `check(host.featureIds.containsAll(WAVE_B_FEATURE_IDS))`
  - if waveA: `check(host.featureIds.containsAll(WaveAWiring.WAVE_A_FEATURE_IDS))` + `waveA.assertAllServicesNonNull()`

**File:** `...\featurehost\WaveAWiring.kt`

- `WAVE_A_FEATURE_IDS` from `AdminFeatureModule`, `AutoSetupModule`, `ModelhubModule`, `PlaygroundModule`, `ServerFeatureModule`, `DashboardFeatureModule` FEATURE_ID constants.
- `wire()` constructs: admin, autoSetup, modelHub, playground, server, dashboard APIs; then `assertAllServicesNonNull()`.

**File:** `...\featurehost\WaveAFeaturePacks.kt`

- Holds non-null fields for all six Wave-A services + orchestrator/governor/engine binding.

### E4 — FEATURE_ID constants match catalog (sampled / grepped)

| Module file | FEATURE_ID |
|-------------|------------|
| `features/admin/.../AdminFeatureModule.kt` | `FEAT-ADMIN` |
| `features/auto-setup/.../AutoSetupModule.kt` | `FEAT-AUTOSETUP` |
| `features/modelhub/.../ModelhubModule.kt` | `FEAT-MODELHUB` |
| `features/playground/.../PlaygroundModule.kt` | `FEAT-PLAYGROUND` |
| `features/server/.../ServerFeatureModule.kt` | `FEAT-SERVER` |
| `features/dashboard/.../DashboardFeatureModule.kt` | `FEAT-DASHBOARD` |
| `features/lan/.../LanFeatureModule.kt` | `FEAT-LAN` |
| `features/benchmark/.../BenchmarkFeatureModule.kt` | `FEAT-BENCHMARK` |
| `features/diagnostics/.../DiagnosticsModule.kt` | `FEAT-DIAGNOSTICS` |
| `features/routing/.../RoutingFeatureModule.kt` | `FEAT-ROUTING` |
| `features/tools/.../ToolsFeatureModule.kt` | `FEAT-TOOLS` |
| `features/ai-content-report/.../ContentReportModule.kt` | `FEAT-AI-CONTENT-REPORT` |

### E5 — Gradle: runtime-service depends on all 12 feature modules

**File:** `android/runtime-service/build.gradle.kts` lines ~129–143:

- Wave-A: `:features:admin`, `auto-setup`, `modelhub`, `playground`, `server`, `dashboard`
- Wave-B: `:features:ai-content-report`, `lan`, `benchmark`, `diagnostics`, `routing`, `tools`

**File:** `settings.gradle.kts` features include block — same 12 modules.

### E6 — Downstream surfaces consume plane-hosted APIs

- `GatewayLifecycle` injects plane Wave-B APIs into `ControlPlaneHttpHandler` (`lanPorts`, `diagnosticsApi`, `contentReportApi`, `routingApi`, `toolsApi`, `benchmarkApi`).
- `OmniAdminFacade` / `OmniRuntimeFacade` call `plane.modelHubApi`, `plane.playgroundApi`, `plane.developerServerApi`, `plane.contentReportApi`, etc.
- Host README: `featurehost/README.md` — “**only** place Feature Pack APIs are constructed with control-plane ports”.

### E7 — Unit tests on the same bootstrap path

- `FeaturePackHostBootstrapTest.bootstrap_attachesAllWaveBPacks` — all 6 Wave-B APIs non-null + featureIds.
- `RuntimeControlPlaneWaveASmokeTest.waveA_allFeatureServicesNonNull` — all 6 Wave-A services.
- `RuntimeControlPlaneWaveASmokeTest.waveA_attachedOnFeaturePackHost_exposesServices` — host with `waveA` contains **both** `WAVE_A_FEATURE_IDS` and `WAVE_B_FEATURE_IDS` (12 total).

### E8 — Hosting matrix (L2)

| Feature ID | Module exists (L1) | Constructed in attach (L2) | Held on plane | Evidence symbols |
|------------|--------------------|----------------------------|---------------|------------------|
| FEAT-ADMIN | PASS | PASS | PASS | `WaveAWiring.wire` → `admin`; `plane.adminFeatureApi` |
| FEAT-AUTOSETUP | PASS | PASS | PASS | `autoSetup`; `plane.autoSetupApi` |
| FEAT-MODELHUB | PASS | PASS | PASS | `modelHub`; `plane.modelHubApi` |
| FEAT-PLAYGROUND | PASS | PASS | PASS | `playground`; `plane.playgroundApi` |
| FEAT-SERVER | PASS | PASS | PASS | `server`; `plane.developerServerApi` |
| FEAT-DASHBOARD | PASS | PASS | PASS | `dashboard`; `plane.dashboardApi` |
| FEAT-LAN | PASS | PASS | PASS | `lanApi`; `plane.lanApi` |
| FEAT-BENCHMARK | PASS | PASS | PASS | `benchmarkApi`; `plane.benchmarkApi` |
| FEAT-DIAGNOSTICS | PASS | PASS | PASS | `diagnosticsApi`; `plane.diagnosticsApi` |
| FEAT-ROUTING | PASS | PASS | PASS | `routingApi`; `plane.routingApi` |
| FEAT-TOOLS | PASS | PASS | PASS | `toolsApi`; `plane.toolsApi` |
| FEAT-AI-CONTENT-REPORT | PASS | PASS | PASS | `contentReportApi`; `plane.contentReportApi` |

**Count:** 12/12 L2 hosted on production `RuntimeControlPlane.attach` path.

---

## Counter-evidence (reviewed; does not falsify L2 hosting)

1. **UI also constructs Feature Module APIs**  
   `android/app-ui/.../AdminLiveFeatureFactory.kt` builds ViewModel-layer `createApi` projections over `IOmniAdmin` (e.g. AutoSetup, Playground, Server, LAN). This is **client projection / composition**, not alternate single-writer hosting. Mutations still go through binder → plane. Does **not** mean packs are absent from RCP.

2. **HTTP markers cover Wave-B only**  
   `ControlPlaneHttpHandler.attachedFeaturePackMarkers()` maps only lan/benchmark/diagnostics/routing/tools/ai-content-report. Wave-A is reached via Admin binder / other plane accessors. Incomplete HTTP marker list ≠ missing host construction.

3. **Hermetic bootstrap without `waveA` has only 6 packs**  
   `FeaturePackHost.bootstrap` without `waveA` omits Wave-A (documented for unit fixtures). Production `attach` **always** passes `waveA = waveA`. Claim is about production plane hosting.

4. **Fail-closed ports until engines attach**  
   Inference-backed packs may use `FailClosed*` / honest UNKNOWN capabilities until `ensureEnginePacksAttached`. Packs are still **constructed and hosted**; capability support is a separate claim.

5. **No device instrumented test asserting `featureIds.size == 12` after real process attach**  
   JVM unit tests cover the same bootstrap functions; production attach log prints featureIds. Residual test gap does not remove static code evidence of hosting.

6. **ID alias in product docs package map**  
   Product `specs/feature-capability-map.yaml` uses `FEAT-AI-REPORTING`; docs README / monorepo / code use `FEAT-AI-CONTENT-REPORT`. Still one pack, not a 13th or missing pack.

---

## Residual risk / out of scope

| Item | Note |
|------|------|
| L3 product journeys | Software-complete end-to-end UI journeys per pack **not** asserted by this claim |
| Engine QUALIFIED/SUPPORTED | Not elevated by Feature Pack hosting |
| Device / Play / OEM matrix | No invented results |
| Dual API construction in UI | Architectural smell for some packs (projection vs pure binder-only); orthogonal to RCP hosting PASS |
| ARC-04 late-bind holders | ToolsApi/BenchmarkApi late-bound into Wave-A adapters; still hosted on same plane |

---

## Search log (fail-closed discipline)

| Action | Path / pattern | Result |
|--------|----------------|--------|
| list_dir | Product docs, monorepo root, featurehost | Located specs + runtime-service featurehost |
| read | `docs/70-features/README.md`, `extension-architecture.md` | 12 FEAT-* packs |
| read | `specs/feature-capability-map.yaml` (product + monorepo) | 12 featureIds; AI-REPORT ID alias |
| read | `RuntimeControlPlane.kt` (header + attach ~500–620) | WaveA wire + FeaturePackHost.bootstrap |
| read | `FeaturePackHost.kt`, `WaveAWiring.kt`, `WaveAFeaturePacks.kt`, featurehost README | 6+6 IDs, checks, bootstrap |
| read | `runtime-service/build.gradle.kts`, `settings.gradle.kts` | 12 module deps/includes |
| grep | `FEATURE_ID` / `FEAT-` under `features/` | All 12 module constants |
| grep | plane API usage in binder/http | Wave-A + Wave-B consumers |
| read | `AdminLiveFeatureFactory.kt` | UI projection counter-evidence |
| read | `FeaturePackHostBootstrapTest`, `RuntimeControlPlaneWaveASmokeTest` | 12-ID host test when waveA present |

---

## Conclusion

**`real: true`** — Under the product definition of 12 Feature Packs and the monorepo’s production `RuntimeControlPlane.attach` path, **all 12 packs are hosted (L2)** on the runtime control plane via `WaveAWiring` + `FeaturePackHost`. Claims beyond L2 hosting (journeys, qualification, device matrices) remain out of scope and unproven by this verification.
)
