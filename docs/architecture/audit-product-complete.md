# OmniLLM Android — Adversarial Product-Complete Audit

**As-of:** 2026-08-06  
**Repo:** `omnillm-android/` (edit surface only)  
**Docs authority:** product package `OmniLLM_Product_Documents` + in-repo `specs/`  
**Rules:** INV-001, ADR-010, Plan→Reserve→Commit→Execute (Plan pure), no invented QUALIFIED/SUPPORTED, fail-closed UNKNOWN, no Play upload automation.

> **Fail closed:** missing evidence = **FAIL** in this note.  
> Completing software gates does **not** invent device PASS evidence, OEM matrix, or Play Console approval.

---

## 1) Executive result

| Gate | Result | Notes |
|---|---|---|
| Software path for all 12 FEAT packs (control plane host) | **PASS** | `WaveAWiring` + `FeaturePackHost.bootstrap` on `RuntimeControlPlane.attach` |
| Durable ADR-010 writer (claim/commit/session/jobs/secrets/tools/reports/models) | **PASS** (code evidence) | SQLite ledgers via `ControlPlaneDatabase`; no production `createInMemoryWithCommits` |
| Engine qualification honesty | **PASS** | All engines UNQUALIFIED / UNKNOWN; exploratory = CONDITIONAL only |
| IOmniRuntime chat non-stub software path | **PASS** (code) | `OmniRuntimeFacade.startOrchestratedChat` → Orchestrator PRCE |
| UI LOCAL_UI playground/server smoke non-stub path | **PASS** (code this turn) | New `IOmniAdmin` methods → plane playground/server APIs |
| Host unit/assemble re-verify this session | **FAIL** (environment) | Gradle ZIP locks / daemon stop / incomplete build-cache for `engines:api` — see §7 |
| Device / OEM / Play | **OUT OF SCOPE** | Human residuals |

**Software READY-TO-LAUNCH (code architecture):** YES, with honest engine UNKNOWN/UNQUALIFIED and human residuals.  
**Software READY-TO-LAUNCH (this host green tests):** **FAIL until** clean `./gradlew test` + assemble completes (environment lock residual, not product design gap).

---

## 2) Hard rules check

| ID | Rule | Evidence | Status |
|---|---|---|---|
| INV-001 | UI never loads native / never writes DB | `AdminRuntimeConnection` binds only `AdminBindingService`; app-ui deps exclude engine native load; UI projections use binder/HTTP only | **PASS** |
| ADR-010 | Only runtime control plane durable writer | `RuntimeControlPlane.attach` + `SingleWriterPolicy` + `ControlPlaneDatabase` | **PASS** |
| PRCE | Plan has no domain mutation | Engine Plan paths + Orchestrator; Plan pure | **PASS** |
| ENG-Q | Never mark QUALIFIED/SUPPORTED without evidence | `specs/engine-qualification-status.yaml`; `EngineSelectionPolicy`; attach asserts | **PASS** |
| INV-018 | Fail closed unknown; no silent cross-revision | `EngineExecuteBinding.resolveCapability`; candidate `engineBuildId` match | **PASS** |
| PLAY | No Play upload automation with secrets | `release.yml` build only; `gradle/RELEASE_CHECKLIST.md` human | **PASS** |

---

## 3) All 12 features — non-stub software path or residual

Legend: **PASS** = domain + plane host + primary transport/UI path non-permanent-stub.  
**PARTIAL** = plane path OK; secondary surface residual documented.  
**FAIL** = permanent stub with no honest residual / no plane path.

| # | Feature | Plane host | Primary UI / transport | Status | Residual |
|---|---|---|---|---|---|
| 1 | FEAT-ADMIN | Wave-A `AdminFeatureModule` + `AdminApiService` | `IOmniAdmin` jobs/settings/snapshot | **PASS** | Full catalog command surface depth |
| 2 | FEAT-AUTOSETUP | Wave-A `AutoSetupModule` | Onboarding + fixture catalog + jobs | **PASS** | UI models/orchestrator ports still FailClosed for some setup probes; device discovery host-dependent |
| 3 | FEAT-MODELHUB | Wave-A + durable model manager / model-store | ModelHub screen + Admin jobs | **PASS** | Display/link ports still in-memory (low severity) |
| 4 | FEAT-PLAYGROUND | Wave-A + Orchestrator ports | **UI:** `IOmniAdmin.executePlaygroundChat` (this turn); **AIDL:** `IOmniRuntime.chat` orchestrated | **PASS** | Real GGUF quality human-only; exploratory flag default off |
| 5 | FEAT-SERVER | Wave-A + loopback gateway | Server UI + HTTP; smoke via Admin (this turn); HTTP sync chat exploratory path (this turn) | **PASS** | Token/client admin UI ports partial; OpenAPI edge breadth |
| 6 | FEAT-LAN | Wave-B + `ControlPlaneLanHost` TLS | Server LAN tab + `OmniDestination.Lan` | **PASS** | UI pairing still FailClosed residual; device pairing human |
| 7 | FEAT-DASHBOARD | Wave-A observability projection | Dashboard tab | **PASS** | Live charts polish |
| 8 | FEAT-BENCHMARK | Wave-B + jobs | Benchmark destination + screen | **PASS** | Measured numbers not invented |
| 9 | FEAT-DIAGNOSTICS | Wave-B export | Diagnostics screen | **PASS** | SAF/share intent on device |
| 10 | FEAT-ROUTING | Wave-B + plane Orchestrator planner | Routing screen | **PASS** | Multi-engine live limited by peer stubs |
| 11 | FEAT-TOOLS | Wave-B durable tool ledger + ToolsApi | Playground STRUCTURED adapter + HTTP tools | **PARTIAL** | No dedicated Tools `OmniDestination`; Admin structured still FailClosed; companion sandbox depth on device |
| 12 | FEAT-AI-CONTENT-REPORT | Wave-B durable SQLite ledger | Content report screen + AIDL report APIs | **PASS** | Play regulatory questionnaire human |

### Critical sites fixed this turn (software)

| Site | Before | After |
|---|---|---|
| `OmniRuntimeFacade.chat` | Already orchestrated; missing `ensureEnginePacksAttached` | Calls `ensureEnginePacksAttached()` before exploratory execute |
| `OmniRuntimeFacade.embed` | Permanent “not yet attached” TODO | Honest `CAPABILITY_UNKNOWN` (embedding unqualified) |
| UI playground inference | Permanent `FailClosedAdminInferencePort` | `AdminPlaygroundInferencePort` → `IOmniAdmin.executePlaygroundChat` |
| UI server smoke | Permanent `FailClosedServerInferencePort` | `AdminServerInferencePort` → `executeServerSmoke` |
| UI capability cells | Always UNKNOWN | `getInferenceCapabilityState` → CONDITIONAL when policy allows |
| HTTP `createChatCompletion` | Always “needs READY candidates” even with orchestrator | Exploratory Orchestrator path when engine bound + flag; else honest fail-closed |
| AIDL surface | No LOCAL_UI inference | Extended `IOmniAdmin` + `specs/aidl/omnillm-aidl.yaml` |

### Remaining software residuals (not elevated to invented evidence)

| ID | Item | Severity |
|---|---|---|
| R-TOOLS-UI | No top-level Tools destination (embedded Playground/HTTP) | Low / product IA |
| R-LAN-PAIR-UI | `FailClosedLanPairingPort` on Admin projection | Medium — plane LAN pairing exists |
| R-AUTOSETUP-UI | FailClosed model/orchestrator ports in UI AutoSetup factory | Medium — plane AutoSetup wired |
| R-MODELHUB-META | InMemory display/link ports | Low |
| R-STRUCTURED-UI | `FailClosedPlaygroundStructuredPort` on Admin | Medium — plane ToolsApi + HTTP |
| R-UPSTREAM | All `UPSTREAM.lock` NOT_LOCKED | Expected for software launch |
| R-ENGINES-PEERS | litert/mlc/mllm/ort stubs UNKNOWN | Honest |

---

## 4) Engines

| Engine | Software | Qualification | Notes |
|---|---|---|---|
| llama-cpp | Adapter + JNI shim packaging + exploratory execute | UNQUALIFIED / UNKNOWN | CONDITIONAL when `runtime.exploratoryExecuteEnabled` |
| LiteRT-LM | Adapter stub | UNQUALIFIED / UNKNOWN | Registry only |
| MLC-LLM | Adapter stub | UNQUALIFIED / UNKNOWN | Registry only |
| mllm | Adapter scaffold | UNQUALIFIED / UNKNOWN | Registry only |
| ORT GenAI | Adapter stub | UNQUALIFIED / UNKNOWN | Registry only |

**Never** invent PASS evidence or SUPPORTED projection.

---

## 5) Production attach evidence (code)

`RuntimeControlPlane.attach` (runtime process only):

1. `ControlPlaneDatabase` + `RequestRegistryModule.createWithCommits`
2. `SessionModule.createDurableManager`
3. `JobManagerModule.createDurableManager` + `reconcileAfterRestart`
4. `ControlPlaneSecurityFactory` (Keystore + SQLite HMAC)
5. `ModelManagerModule.createDurableControlPlane` + filesystem model-store
6. `WaveAWiring.wire` (admin, auto-setup, modelhub, playground, server, dashboard) with `EngineExecuteBinding`
7. `FeaturePackHost.bootstrap` (lan, benchmark, diagnostics, routing, tools, content-report) with durable ledgers
8. Engines after READY/DEGRADED via `ensureEnginePacksAttached` → bind llama adapter

---

## 6) Files changed this turn

| Path | Change |
|---|---|
| `interfaces/aidl/.../IOmniAdmin.aidl` | LOCAL_UI playground chat/query/cancel, server smoke, capability state |
| `specs/aidl/omnillm-aidl.yaml` | Spec parity for new Admin methods |
| `android/runtime-service/.../OmniAdminFacade.kt` | Implement Admin inference proxies → plane APIs |
| `android/runtime-service/.../OmniRuntimeFacade.kt` | `ensureEnginePacksAttached` on orchestrated chat |
| `android/runtime-service/.../ControlPlaneHttpHandler.kt` | Exploratory sync chat path |
| `android/app-ui/.../AdminFeatureProjections.kt` | Non-stub playground/server inference projections |
| `android/app-ui/.../AdminLiveFeatureFactory.kt` | Comment accuracy |
| `gradle.properties` | `kotlin.compiler.execution.strategy=in-process`, parallel false (host stability) |
| `docs/architecture/audit-product-complete.md` | This audit |

---

## 7) Tests run (this session)

| Command | Result |
|---|---|
| `./gradlew :engines:api:jar` / multi-module compile chains | **FAIL / unstable** — Windows file locks on `core/canonical/build/libs/*.jar`, intermittent “Gradle build daemon has been stopped: stop command received”, stale build-cache producing incomplete `engines:api` JARs (missing `EnginePhases` etc. when cache hit) |
| Full `./gradlew test` | **Not completed** this session (blocked by above) |
| assembleRelease | **Not re-run** this session |

**Prior green evidence (repo logs, earlier pass):** `PRODUCT_READINESS.md` / `GAP_CLOSEOUT.md` report `test --continue` BUILD SUCCESSFUL and assembleDebug/Release green on this monorepo — treat as historical; re-verify after host lock cleanup.

### Required re-verify (human / clean host)

```powershell
cd C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android
# Stop all Java/Gradle, delete stale build dirs if locks persist
.\gradlew.bat --stop
.\gradlew.bat clean
.\gradlew.bat test --no-build-cache
.\gradlew.bat :android:app-ui:assembleDebug :android:app-ui:assembleRelease `
  :android:companion-sandbox:assembleDebug :android:companion-sandbox:assembleRelease
```

---

## 8) Residual human-only items

1. Physical device install / FGS / thermal / real GGUF  
2. OEM / device qualification matrix — no invented PASS  
3. Upstream engine locks (NOT_LOCKED)  
4. Play Console signing, Data Safety, listing, AI content questionnaires  
5. R8 minify smoke after human sign-off  
6. Enable `runtime.exploratoryExecuteEnabled` only via LOCAL_ADMIN for CONDITIONAL generate  
7. Host re-verify unit tests + assemble after clearing Gradle/Windows file locks  

---

## 9) Adversarial notes (fail closed)

| Claim | Verdict |
|---|---|
| “All 12 features are stubs” | **False** — plane hosts all 12; UI inference stub for playground/server closed this turn |
| “Production still uses InMemory claim/commit” | **False** — `createWithCommits` + SQLDelight (see `GAP_INVENTORY.md` as **stale**) |
| “Engines are SUPPORTED” | **False** — remain UNKNOWN/UNQUALIFIED |
| “Software ready = device ready” | **False** — device evidence out of scope |
| “This session’s compile is green” | **FAIL** — environment locks; do not claim green without re-run |

---

## 10) Authority cross-check

| Source | Use |
|---|---|
| `GAP_INVENTORY.md` | Historical only (pre-closeout) |
| `GAP_CLOSEOUT.md` | Gaps 1–3 done |
| `PRODUCT_READINESS.md` | Software YES narrative (pre this audit’s host re-verify FAIL) |
| `PRODUCT_READINESS_CHECKLIST.md` | Partially stale vs attach path; D3c/d/jobs/secrets now done in code; UI inference items closed this turn |
| `FEATURE_AUDIT.md` | Align: FEAT-TOOLS remains PARTIAL (depth/UI) |

---

*End of prior audit section. Append further runs below this line; do not invent PASS evidence.*

---

## 11) Adversarial append — llama native packaging / execute honesty (2026-08-06)

**Focus:** silent fixture vs real GGUF load; capability labels; fail-closed path.

### Findings

| ID | Severity | Verdict | Notes |
|---|---|---|---|
| **LLA-NAT-001** | Critical | **FIXED** | Native `omnillm_llama_load_model` treated broker-only (`!path_or_fd`) as EXPERIMENTAL_FIXTURE. `LlamaCppEngine.commitLoad` never plumbed path/FD → **every** load silently became fixture. Violates fail-closed honesty for real install intent. |
| LLA-NAT-002 | — | **PASS** | No SUPPORTED/QUALIFIED elevation from `.so` presence; matrices stay UNQUALIFIED/UNKNOWN |
| LLA-NAT-003 | — | **PASS** | Production attach does not substitute StubNativeBackend when lib missing |
| LLA-NAT-004 | — | **PASS** | Cross-build fallback rejected in adapter + `EngineExecuteBinding` |
| LLA-NAT-005 | Residual | **FAIL** (honest) | `UPSTREAM.lock` NOT_LOCKED (empty digests); vendor tree pin present `b9999` / `47c7869…`; packaged `.so` ~45–80 MB (upstream linked on this host) |
| LLA-NAT-006 | Residual | **FAIL** (honest) | No device PASS packs — cells correctly UNQUALIFIED |

### Fixes applied (this append)

1. **Native fail-closed load selection** (`omnillm_llama.cpp` / `.h`):
   - Explicit fixture markers only → EXPERIMENTAL_FIXTURE
   - Path/FD → upstream GGUF (or NOT_AVAILABLE if not linked)
   - Broker-only without markers → **NOT_AVAILABLE** (no silent fixture)
2. **`LoadInput.resolvedModelPath` / `modelFd`** optional fields (`engines/api` LoadContracts)
3. **`LlamaCppEngine`**: plan sideband preserves storage/path/FD; commitLoad fails closed without explicit fixture or path/FD; records honest `loadMode`
4. **`LlamaCppInferenceEngineAdapter`**: exploratory load uses explicit `fixture:EXPERIMENTAL_FIXTURE`
5. **Tests**: `realInstallWithoutPathOrFd_failsClosed_noSilentFixture`, `explicitFixtureMarkers_commitLoadSucceeds`
6. **Docs**: `engines/llama-cpp/NATIVE.md`, `docs/architecture/engine-registry-attachment.md`

### Files changed (this append)

| Path | Change |
|---|---|
| `android/native/src/main/cpp/omnillm_llama.cpp` | Fail-closed load selection |
| `android/native/src/main/cpp/omnillm_llama.h` | Document honest load policy |
| `engines/api/.../LoadContracts.kt` | `resolvedModelPath` / `modelFd` on `LoadInput` |
| `engines/llama-cpp/.../LlamaCppEngine.kt` | Sideband + fail-closed resolve |
| `engines/llama-cpp/.../NativeBackend.kt` | KDoc honesty |
| `engines/llama-cpp/.../JniNativeMapping.kt` | Explicit fixture note |
| `engines/llama-cpp/src/test/.../LlamaCppEnginePipelineTest.kt` | New fail-closed tests |
| `android/runtime-service/.../LlamaCppInferenceEngineAdapter.kt` | Explicit fixture markers |
| `engines/llama-cpp/NATIVE.md` | Load selection table |
| `docs/architecture/engine-registry-attachment.md` | Exploratory fixture honesty |
| `docs/architecture/audit-product-complete.md` | This append |

### Tests re-run (this append)

| Command | Result |
|---|---|
| `./gradlew :engines:llama-cpp:test --no-build-cache --no-parallel` | **BUILD SUCCESSFUL** — Pipeline 10/0, UpstreamLock 4/0, Mapping 6/0, JniNativeMapping 10/0, Envelope 4/0 |
| `./gradlew :android:runtime-service:testDebugUnitTest --tests …EngineExecuteBindingTest --tests …EnginePackAttachmentTest --no-parallel` | **BUILD SUCCESSFUL** — EngineExecuteBindingTest 11/0, EnginePackAttachmentTest 7/0 |

### Residual (unchanged human-only)

- Model Manager privileged path/FD broker → populate `LoadInput.resolvedModelPath` / `modelFd` for non-fixture installs
- Complete UPSTREAM digests; device PASS packs; Play submission
- Host-wide `./gradlew test` + assemble may still hit Windows file-lock flakiness (see §7)

---

*Append further runs below; missing evidence remains FAIL; never invent PASS.*
