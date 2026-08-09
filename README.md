# OmniLLM Android Monorepo

Unified **local edge LLM engine platform** — Android first.

This repository is the **implementation monorepo** for the main app (`com.omnillm`) and the ADR-007 companion sandbox (`com.omnillm.companion`). Product design authority lives in the separate OmniLLM product document package; machine-readable contracts under `specs/` are copied from that package and remain the source of truth for types, errors, states, capabilities, OpenAPI, AIDL, and schema.

| Doc | Role |
|---|---|
| **[BUILD_STATUS.md](./BUILD_STATUS.md)** | **Current engineering inventory** — modules, features, engines, known gaps, residual risks |
| **[PRODUCT_READINESS_CHECKLIST.md](./PRODUCT_READINESS_CHECKLIST.md)** | Software launch gate vs human/device-only residuals |
| **[AGENTS.md](./AGENTS.md)** | Module map, dependency rules, how to add Feature/Engine Packs |
| [gradle/RELEASE_CHECKLIST.md](./gradle/RELEASE_CHECKLIST.md) | Human-only Play Console steps (no automated upload) |
| [GAP_CLOSEOUT.md](./GAP_CLOSEOUT.md) | Gaps 1–3 integration closeout evidence |
| [tools/ci/README.md](./tools/ci/README.md) | CI pipeline, signing secrets, local parity |

> Do **not** treat this README as the status authority. For “what works / what’s open,” read **BUILD_STATUS.md** and **PRODUCT_READINESS_CHECKLIST.md**.

## Product authority

| Concern | Authority |
|---|---|
| Machine-readable contracts | `specs/` (from product package) |
| Architecture invariants | product `docs/20-architecture/architecture-invariants.md` |
| Android baseline / Play policy | product `docs/60-android/` + `specs/platform-policy-register.yaml` |
| ADRs | product `governance/adr/` |
| Agent / module rules | [AGENTS.md](./AGENTS.md) |

Do **not** invent types, enums, states, or error codes that are absent from `specs/` catalogs.

## Toolchain lock

Locked in `gradle/libs.versions.toml` and `gradle.properties`:

| Tool | Version | Rationale |
|---|---|---|
| **JDK** | **17** | AGP 9.x minimum / default |
| **Gradle** | **9.5.0** | AGP 9.3.0 minimum |
| **AGP** | **9.3.0** | Current stable supporting compile/target API 36+ (max API 37) |
| **Kotlin** | **2.2.0** | Aligned with AGP 9.x era toolchain |
| **compileSdk** | **36** | Play target Android 16 / API 36 baseline (`PLAY-TARGET-API-2026`) — **locked** |
| **targetSdk** | **36** | Same as compileSdk for Play submission readiness — **locked** |
| **minSdk** | **28** | **Product decision** (see below) |
| **NDK** | **28.2.13676358** | AGP 9.3 default; r28+ default 16 KB alignment (`ANDROID-16KB`) |
| **Build Tools** | **36.0.0** | AGP 9.3 default |
| **App version** | **0.1.0** (`versionCode` 1) | Coherent main + companion software release line |

Play deploy gates (manual Console + build): [`gradle/RELEASE_CHECKLIST.md`](./gradle/RELEASE_CHECKLIST.md).  
Data Safety inventory: [`android/app-ui/play/DATA_SAFETY_INVENTORY.md`](./android/app-ui/play/DATA_SAFETY_INVENTORY.md).  
Companion separate APK: [`android/companion-sandbox/PACKAGING.md`](./android/companion-sandbox/PACKAGING.md).

### minSdk decision (product / engineering)

`ANDROID-BASELINE` states that `minSdk` is a product decision, not an architecture invariant.

**Choice: minSdk = 28 (Android 9.0).**

1. On-device LLM + multi-engine native stacks need 64-bit capable, relatively recent devices; API 28 is a practical floor for edge inference hardware.
2. Avoids pre-P storage / process edge cases while still covering a wide install base.
3. FGS, Binder, and SAF patterns used by the control plane are mature by API 28+.
4. Can be raised later (never silently lowered without re-validating engines and policy).

Non-Play sideload builds still use the same targetSdk and must not relax trust, exported components, or model trust (`ANDROID-BASELINE` §2).

## Hard architecture rules (summary)

1. **INV-001** — UI process never loads native engines or writes DB / model store.
2. **ADR-002** — Plan → Reserve → Commit → Execute; Plan has no domain mutation.
3. **ADR-010** — Single writer: only runtime control plane writes DB / model store / tokens.
4. **ADR-008** — Blob / ArtifactPackage / ModelRevision / Installation are distinct identities.
5. **ADR-009** — Trust, compatibility, performance, license, placement are separate dimensions.
6. **ADR-007** — Untrusted accelerated inference requires different-package UID companion.
7. **ADR-004/005** — Client-generated `requestId` / `idempotencyKey`; claim-or-return; query on reply loss.
8. **ADR-011** — HTTP / AIDL / UI share canonical semantics; transport guarantees may differ.
9. **INV-018** — Unknown capability / cancel / envelope ⇒ fail closed.
10. **Engine honesty** — Never mark engine cells QUALIFIED/SUPPORTED or invent device PASS evidence. Capability matrix stays UNKNOWN/UNQUALIFIED without evidence. Runtime may allow **CONDITIONAL exploratory execute** for packaged native when policy permits.

Full agent/module rules: see [AGENTS.md](./AGENTS.md).

## Repository layout

```text
omnillm-android/
  specs/                 # Canonical machine-readable authority (copy of product specs)
  core/                  # Portable pure-Kotlin domain (canonical, state, contracts, …)
  data/                  # Persistence + model store (control-plane writers only)
  runtime/               # Control plane: registry, orchestrator, governor, managers
  engines/               # Engine Pack adapters (api + per-engine)
  interfaces/            # HTTP / AIDL / Admin adapters
  features/              # Feature Packs (12)
  android/               # Process topology: app-ui, runtime-service, workers, sandboxes, native
  tools/codegen/         # Spec-driven codegen
  tools/ci/              # CI helpers (drift, dep edges, 16 KB)
  docs/architecture/     # Implementation architecture notes
  gradle/libs.versions.toml
  BUILD_STATUS.md
  PRODUCT_READINESS_CHECKLIST.md
  AGENTS.md
```

Dependency edges point **inward only** (Experience → Interface → Control Plane → Engine/Data/Platform). See `AGENTS.md`.

**Packages / processes**

| Artifact | applicationId | Process role |
|---|---|---|
| `:android:app-ui` | `com.omnillm` (+ `.debug` suffix) | Default UI process |
| `:android:runtime-service` | merged into main app | `:runtime` (FGS, Admin/Runtime binder, loopback HTTP) |
| `:android:workers` | merged | `:engine_worker` (same-UID crash isolation) |
| `:android:parser-isolated` | merged | `:parser` (`isolatedProcess`) |
| `:android:companion-sandbox` | `com.omnillm.companion` | Different UID companion (separate APK) |
| `:android:native` | AAR / jni | Packaged `libomnillm_llama.so` (JNI shim; 16 KB gates) |

## Status (software)

Implementation is **beyond skeleton**: full module graph, 12 Feature Packs hosted on the control plane, durable claim/commit/session SQLite, AIDL/HTTP transports, Compose UI shell, and a packaged llama JNI **upstream** (vendored llama.cpp b9999, LOCKED, real GGUF generation verified on emulator).

| Area | Snapshot |
|---|---|
| Modules | 43 Gradle modules (`settings.gradle.kts`) |
| Feature Packs | 12/12 constructed on control plane |
| Engine Packs | 5 adapters + `engines:api`; llama.cpp upstream **LOCKED + device-verified**; LiteRT/MLC/mllm/ORT adapters fail-closed (vendor artifacts pending) |
| Native | `libomnillm_llama` CMake/JNI links vendored llama.cpp b9999 for arm64-v8a + x86_64; 16 KB gates pass |
| Release assemble | `:android:app-ui:assembleRelease` + `:android:companion-sandbox:assembleRelease` |
| Honesty | No QUALIFIED/SUPPORTED cells without device evidence |

**Authoritative detail:** [BUILD_STATUS.md](./BUILD_STATUS.md).  
**Software vs human gates:** [PRODUCT_READINESS_CHECKLIST.md](./PRODUCT_READINESS_CHECKLIST.md).

Human/device residuals (not closed by software alone): physical multi-process E2E, OEM/engine qualification PASS cells, Play Console upload/signing ceremony, Data Safety / FGS / AI-reporting questionnaires.

## Build

Requirements: JDK 17+, Android SDK with platform 36, NDK 28.2.x (when native modules build), Python 3 for contract/16 KB gates.

```bash
# List modules
./gradlew omnillmModules

# Generate formal contracts from specs/ into core/canonical|state|errors
pip install -r tools/codegen/requirements.txt
./gradlew generateContracts          # or: ./gradlew toolsCodegen
./gradlew checkContractDrift         # CI: fail on catalog vs generated drift

# Architecture / packaging gates
./gradlew checkModuleDependencyRules # INV-001 / ADR-010 / ADR-007
./gradlew checkDependencyEdges       # INV-001 UI isolation / engines↛data
./gradlew checkNative16kb            # ELF 16 KB LOAD alignment
./gradlew check                        # drift + 16 KB + dep edges + module rules

# Unit + host tests (all Kotlin JVM + Android testDebugUnitTest; no device required)
./gradlew test
./gradlew jvmTest
./gradlew androidUnitTest

# Assemble (unsigned release OK without signing secrets)
./gradlew :android:app-ui:assembleDebug
./gradlew :android:app-ui:assembleRelease
./gradlew :android:app-ui:bundleRelease
./gradlew :android:companion-sandbox:assembleDebug
./gradlew :android:companion-sandbox:assembleRelease
```

Windows: `.\gradlew.bat` with the same tasks.

### CI / CD

| Surface | Location |
|---|---|
| PR / push workflow | [`.github/workflows/ci.yml`](./.github/workflows/ci.yml) |
| Signed release (optional secrets; **no** Play upload) | [`.github/workflows/release.yml`](./.github/workflows/release.yml) |
| Dependabot (Gradle + Actions) | [`.github/dependabot.yml`](./.github/dependabot.yml) |
| Local parity scripts | [`tools/ci/local_ci.sh`](./tools/ci/local_ci.sh), [`tools/ci/local_ci.ps1`](./tools/ci/local_ci.ps1) |
| Secrets & gates | [`tools/ci/README.md`](./tools/ci/README.md) |

```bash
# Full local gate (Unix)
bash tools/ci/local_ci.sh

# Windows PowerShell
.\tools\ci\local_ci.ps1
```

CI runs (fail closed): specs present → **contract drift** → generate + clean tree → **module dep rules** → **dep edges** → **unit tests** → root `check` → lint → **assembleDebug + assembleRelease** → **16 KB ELF + APK zip-align** → upload APK artifacts.

**detekt** is **not** configured — intentionally skipped (documented in `tools/ci/README.md` and `BUILD_STATUS.md`). AGP lint + unit tests + architecture gates remain required.

**Never commit signing keystores.** Configure optional GitHub secrets documented in `tools/ci/README.md`. Play Console upload is **manual** only (`gradle/RELEASE_CHECKLIST.md`).

See [docs/architecture/testing.md](./docs/architecture/testing.md) for fixture mapping and focused `--tests` filters.

If the Gradle Wrapper JAR is not yet committed, generate it once with a local Gradle 9.5+ install:

```bash
gradle wrapper --gradle-version 9.5.0
```

## Product document package

Validated separately with:

```bash
python <product_package>/tools/validate_repository.py <product_package>
```

This monorepo must not modify the product package. Re-copy `specs/` when the product package revisions contracts.
