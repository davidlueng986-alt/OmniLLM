# Adversarial verify — Claim #0

**Claim:** INV-001 — UI process never loads native engines or writes DB (／model store)  
**Auditor:** independent re-inspection (docs package 新版本 + monorepo source only)  
**Date:** 2026-08-12  
**Method:** `list_dir` / `grep` / `read_file` / `run_terminal_command` (CI dependency gates) on real paths; fail-closed if no evidence.  
**Docs authority:** `docs/20-architecture/architecture-invariants.md` INV-001; `specs/traceability-matrix.yaml` INV-001 → ARCH-LOGICAL, ANDROID-SERVICE, Q-014.

## Verdict

| Field | Value |
|---|---|
| **real** | **true** |
| **software_status** | **PASS** (L1+L2: process topology + UI source + attach gates + hard dep edges; L3 product journey process-isolation runtime probe not fully automated) |
| **device_recovery_status** | **N_A** for this claim (claim is process isolation / write authority, not kill-at-boundary recovery) |

### reason

The **new** docs package states INV-001 as: UI process 不載入 native engine、不直接寫 DB／model store. Monorepo implements that as: (1) main UI process bootstrap does **not** attach control plane; (2) domain DB open + engine host live only under `:runtime` services (`android:process=":runtime"`); (3) UI code paths bind **only** non-exported `IOmniAdmin` and use feature ViewModels as binder projections; (4) `android/app-ui` source has **zero** `System.loadLibrary` / Room / SQLite / `ControlPlaneDatabase` write sites; (5) hard Gradle edge gates forbid UI→`:data:persistence` and UI→native engine packs (scripts exit 0). Residual risk is packaging/classpath cohabitation and soft SPI debt—not evidence that UI currently loads engines or opens domain writers.

---

### real: true — concrete evidence

#### A) Normative claim text (docs package 新版本)

**File:** `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\docs\20-architecture\architecture-invariants.md`

```text
| `INV-001` | UI process 不載入 native engine、不直接寫 DB／model store。 |
```

**Traceability:** `...\specs\traceability-matrix.yaml` — `id: INV-001` → authorities ARCH-LOGICAL, ANDROID-SERVICE; quality scenario Q-014.

**Process topology (docs):** `...\docs\20-architecture\process-trust-topology.md` — `main` = UI/navigation/local view state only; `:runtime` = Control Plane, DB/model store owner, trusted engine.

#### B) UI process bootstrap — no control plane / no DB / no native

**File:** `...\android\app-ui\src\main\kotlin\com\omnillm\ui\OmniApplication.kt`

```text
 * Process branching (ARCH-TRUST-TOPOLOGY / INV-001):
 * - **main UI**: no control plane, no domain DB writer, no native engines.
 * - **:runtime**: attaches [com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane].
...
            ProcessIdentity.isRuntimeProcess() ->
                RuntimeProcessBootstrap.onRuntimeProcessCreate(this)

            ProcessIdentity.isMainUiProcess(this) -> {
                RuntimeProcessBootstrap.onMainUiProcessCreate(this)
                uiSession = UiSession(this).also { it.start() }
            }
```

**File:** `...\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\controlplane\RuntimeProcessBootstrap.kt`

```text
    fun onMainUiProcessCreate(context: Context) {
        check(ProcessIdentity.isMainUiProcess(context)) {
            "UI bootstrap only valid in main process " +
                "(current=${ProcessIdentity.currentProcessName()})"
        }
        // INV-001: no control plane, no DB writer, no native engine load.
        Log.i(TAG, "UI process bootstrap (Admin binder only; no control plane)")
    }
```

Contrast — runtime attach only when process is `:runtime`:

```text
    fun onRuntimeProcessCreate(context: Context) {
        check(ProcessIdentity.isRuntimeProcess()) { ... }
        RuntimeControlPlane.attach(context)
```

#### C) Control plane attach + DB open process-gated (sole writer path)

**File:** `...\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\controlplane\RuntimeControlPlane.kt`

```text
        fun attach(context: Context): RuntimeControlPlane {
            check(ProcessIdentity.isRuntimeProcess()) {
                "RuntimeControlPlane.attach refused outside :runtime process " +
                    "(current=${ProcessIdentity.currentProcessName()}) — INV-001 / ADR-010"
            }
            SingleWriterPolicy.assertWriterAllowed(SingleWriterPolicy.WRITER_ROLE)
            ...
                val driver = AndroidSqliteDriver(
                    schema = OmniLlmDatabase.Schema,
                    context = appContext,
                    name = StorageLayout.DATABASE_NAME,
                )
                val controlDb = ControlPlaneDatabase.open(
                    driver = driver,
                    applySchema = false,
                    clock = clock,
                )
```

**File:** `...\android\runtime-service\src\main\AndroidManifest.xml` — all control-plane services force `:runtime`:

```text
            android:process=":runtime"
```

(for `RuntimeForegroundService`, `RuntimeBindingService`, `AdminBindingService`, `TransferService`).

Workers/parser also non-UI: `android:process=":engine_worker"` / `:parser`.

**File:** `...\core\ports\src\main\kotlin\com\omnillm\core\ports\ledger\SingleWriterPolicy.kt`

```text
 * | `:android:app-ui` | **No** (INV-001) |
...
    val FORBIDDEN_WRITER_ROLES: Set<String> = setOf(
        "app-ui",
        ...
    )
    fun assertWriterAllowed(role: String) {
        require(role == WRITER_ROLE) {
            "Domain DB write refused for role='$role' ($ADR / $INVARIANT). " +
```

#### D) UI surface is Admin binder + projections only (no engine load / no model-store write)

**File:** `...\android\app-ui\src\main\kotlin\com\omnillm\ui\session\UiSession.kt`

```text
 * INV-001: never opens domain DB writers or loads native engines.
 * Feature VMs are pure projections fed by Admin binder when connected.
...
            attachFeatureViewModels(
                adminHome = BinderAdminFeatureFactory.createHomeViewModel(admin),
                ...
                modelHub = AdminLiveFeatureFactory.createModelHubViewModel(admin),
```

**File:** `...\android\app-ui\src\main\kotlin\com\omnillm\ui\admin\AdminRuntimeConnection.kt`

```text
 * UI-process client that binds **only** [AdminBindingService] (INV-001 / ANDROID-SERVICE).
 *
 * Must never open domain DB writers or load native engines from the UI process.
```

**File:** `...\android\app-ui\src\main\kotlin\com\omnillm\ui\admin\ModelHubLocalImporter.kt` — UI hashes content URI / RO PFD then delegates import to Admin (no model-store write in UI):

```text
 * UI never writes model-store / DB (INV-001 / ADR-010).
...
            val info = admin.importLocalFile(
                pfd,
                ...
```

**File:** `...\features\modelhub\src\main\kotlin\com\omnillm\features\modelhub\ModelhubModule.kt` — full control-plane API / acquisition pipeline explicitly **not** for UI:

```text
     * Wire control-plane dependencies. Call only from runtime host
     * (`:android:runtime-service`), never from UI process.
...
     * Control-plane / host tests only — never constructed in UI process (ADR-010).
    fun createAcquisitionPipeline(
```

UI uses `ModelhubModule.createViewModel(AdminProjectedModelHubApi(admin))` only (`AdminLiveFeatureFactory`).

#### E) Negative greps under `:android:app-ui` source (load / domain DB)

| Pattern | Path | Result |
|---|---|---|
| `System.loadLibrary` / `loadLibrary` / `native fun` / `System.load(` | `android/app-ui` (`.kt`/code) | **No matches** (only proguard comments re: JNI keep for merged runtime) |
| `Room` / `SQLite` / `SqlDelight` / `ControlPlaneDatabase` / `SQLiteOpenHelper` / `AndroidSqliteDriver` | `android/app-ui` | **No matches** |
| `SharedPreferences` / `DataStore` | `android/app-ui` | **No matches** |
| `tryLoadLibrary` / `JniNativeBridge` | `android/app-ui` | **No matches** |

Native load call sites exist **outside** UI only, e.g.:

- `engines/llama-cpp/.../JniNativeBridge.kt` — `System.loadLibrary(JniNativeMapping.LIBRARY_NAME)` inside `tryLoadLibrary()`
- `engines/ort-genai/.../OrtGenAiRuntime.kt` — comment that `GenAI.init()` → `System.loadLibrary`
- Packaged via `runtime-service` → `implementation(project(":engines:llama-cpp"))` + `:android:native` (not direct app-ui edges)

#### F) Module / classpath hard gates (executed this audit)

```text
cd omnillm-android
python tools/ci/check_module_dependency_rules.py --repo-root .
→ OK: hard dependency boundary rules passed
  (hard forbids UI→:data:persistence and UI→native engine packs)

python tools/ci/check_dependency_edges.py
→ check_dependency_edges: OK modules_with_project_deps=41 warnings=3
```

**Hard-forbidden direct edges** (`tools/ci/check_dependency_edges.py`):

```text
(":android:app-ui", ":engines:api", ...),
(":android:app-ui", ":engines:llama-cpp", ...),
...
(":android:app-ui", ":data:persistence", "UI must not depend on domain DB (INV-001 / ADR-010)"),
(":android:app-ui", ":data:model-store", "UI must not depend on model-store writers (INV-001)"),
```

**app-ui `build.gradle.kts`:** no `project(":engines:*")` or `project(":data:*")`; features + admin + runtime-service for process/manifest merge only.

**Instrumented topology smoke (not a loadLibrary probe):** `android/app-ui/src/androidTest/.../RuntimeServiceInstrumentedSmokeTest.kt` asserts `RuntimeBindingService` process ends with `:runtime` and Admin is non-exported (Q-014 surface).

---

### counter_evidence

Items that look like violations but **do not** establish UI-process load/write under current call graphs:

1. **Same APK packages natives + engines with UI**  
   - `app-ui` → `implementation(project(":android:runtime-service"))`  
   - `runtime-service` → `implementation(project(":engines:llama-cpp"|…|:android:native))`  
   - `app-ui/build.gradle.kts` packaging comment: *"UI process must not load engines (INV-001); packaging still inherits merged libs"*  
   - INV-001 is **load/write**, not zip contents. Packaging alone is **not** a falsification.

2. **Soft dependency debt (WARN, not hard fail)** from live scripts:
   - `IMPLEMENTATION :features:modelhub → :data:model-store`
   - `API-PATH :android:app-ui → :features:auto-setup → :runtime:orchestrator → :engines:api`
   - `API-PATH :android:app-ui → :features:routing → :runtime:orchestrator → :engines:api`  
   SPI / model-store **types** may reach compile graph; no evidence UI invokes `tryLoadLibrary` or `ControlPlaneDatabase.open` / `FilesystemModelStorePort` writers.

3. **`check_dependency_edges` WARN:**  
   `UI depends on runtime-service for process/manifest merge; ensure no UI-process control plane attach`  
   Mitigated by process checks in `RuntimeProcessBootstrap` / `RuntimeControlPlane.attach` and service `android:process=":runtime"`.

4. **`JniNativeBridge.tryLoadLibrary()` has no `ProcessIdentity` assert**  
   Load is convention + call-site isolation (engine pack / control plane), not a kernel-enforced process fence inside the JNI wrapper. Still no UI call sites.

5. **`SingleWriterPolicy.assertWriterAllowed` is role-string only**  
   Default `writerRole = SingleWriterPolicy.WRITER_ROLE` on `ControlPlaneDatabase.open` would pass if a non-runtime process called open with the default. Process identity is enforced at **attach**, not inside every DAO open. No UI open call sites found.

6. **UI can start FGS / bind Admin** (`AdminRuntimeConnection.connect` → `RuntimeForegroundService.requestStart`)  
   That runs control plane in **`:runtime`**, not inside the UI process.

---

### residual

| ID | Residual | Severity | Why not falsify today |
|---|---|---|---|
| R0-1 | Merged APK places `.so` + engine classes on same app UID; any future UI mistake can `loadLibrary` | Med | No current UI call site; hard edges block direct engine packs |
| R0-2 | Soft path UI→`engines:api` SPI types via orchestrator | Low | Soft WARN; SPI types ≠ native load |
| R0-3 | `features:modelhub` implementation-depends model-store; acquisition pipeline is control-plane API | Med debt | UI wires `createViewModel(AdminProjected…)` only; `createAcquisitionPipeline` KDoc forbids UI |
| R0-4 | No automated instrumented test that asserts `JniNativeBridge.isLibraryLoaded()==false` **in main process** after launch / playground navigation | Med (L3 gap) | Static + process gates PASS; device load-probe **MISSING** as product evidence |
| R0-5 | Role-string single-writer not process-bound at every DB open | Low–Med | `RuntimeControlPlane.attach` double-checks `:runtime`; UI lacks persistence compile edge for writers |

---

### Search log (empty findings documented)

| Search | Scope | Outcome |
|---|---|---|
| `System.loadLibrary\|loadLibrary\|native fun\|System.load\(` | `android/app-ui` source | Empty for executable loads |
| `Room\|SQLite\|SqlDelight\|ControlPlaneDatabase\|DriverFactory\|JdbcSqlite` | `android/app-ui` | Empty |
| `tryLoadLibrary\|JniNativeBridge` | `android/app-ui` | Empty |
| `System.loadLibrary` (whole repo `*.kt/java/cpp`) | monorepo | Hits only engines/native (not app-ui) |
| `android:process` | `android/**/AndroidManifest.xml` | Services on `:runtime` / `:engine_worker` / `:parser` only |
| CI scripts | monorepo root | Hard INV-001 edges **OK**; 3 soft WARNs listed above |

---

### Levels (auditor convention)

| Level | Meaning | Status for INV-001 |
|---|---|---|
| **L1** | Module / policy artifacts exist | **PASS** — app-ui vs runtime-service vs engines/data split; SingleWriterPolicy; CI gates |
| **L2** | Wired to control plane / process topology | **PASS** — process branch + attach gate + Admin-only UI session |
| **L3** | Product journey software-complete with isolation proof | **PARTIAL** — journeys use binder projections; **missing** runtime probe that main PID never loads `omnillm_llama` / never opens `omnillm.db` writer |

---

### Final claim assessment

| Field | Value |
|---|---|
| **real** | **true** |
| **confidence** | High for software architecture enforcement; residual packaging/SPI debt and missing main-process load probe do not overturn evidence of intended + implemented UI isolation |
| **falsified?** | **No** — no file-path evidence of UI-process native engine load or domain DB / model-store write |
)
