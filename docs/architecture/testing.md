# Running unit and contract tests

Authority: product `PROD-QUALITY` (quality attributes), `specs/quality-scenarios.yaml`,
`specs/command-conformance-fixtures.yaml`, `specs/runtime-recovery-fixtures.yaml`,
`specs/golden-vectors/canonical-encoding.yaml`.

## Quick start (JVM)

From the monorepo root (`omnillm-android/`):

```bash
# All pure-Kotlin / Ktor + Android host unit tests (recommended local CI gate)
./gradlew test
# JVM-only (org.jetbrains.kotlin.jvm modules):
./gradlew jvmTest
# Android library/application host unit tests only:
./gradlew androidUnitTest

# Single module
./gradlew :core:identity:test
./gradlew :core:resource:test
./gradlew :core:state:test
./gradlew :core:contracts:test
./gradlew :runtime:request-registry:test
./gradlew :runtime:orchestrator:test
./gradlew :interfaces:http:test
./gradlew :android:runtime-service:testDebugUnitTest

# Focused suites (examples)
./gradlew :core:state:test --tests "com.omnillm.core.state.CommitReconcileFixtureTest"
./gradlew :core:state:test --tests "com.omnillm.core.state.FsmIllegalEdgesFixtureTest"
./gradlew :runtime:request-registry:test --tests "*CommandConformanceFixturesTest"
./gradlew :interfaces:http:test --tests "*OpenApiRouteSmokeTest"
```

On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

Requirements: **JDK 17+**. Android SDK is **not** required for `jvmTest` /
`./gradlew test` on pure Kotlin modules. Contract codegen may run as a
dependency of some modules (`generateContracts`); Python 3 is needed if catalogs
change and generation is triggered.

## What `./gradlew test` covers

Root `test` depends on:

1. **`jvmTest`** — every subproject that applies `org.jetbrains.kotlin.jvm`
2. **`androidUnitTest`** — every Android library/application `testDebugUnitTest`
   (covers `:android:runtime-service`, `:android:workers`, `:android:companion-sandbox`, …)

`jvmTest` alone is insufficient for binder/process isolation suites. Includes:

| Area | Modules (examples) | Spec fixtures |
|---|---|---|
| Identity / golden digests | `:core:identity`, `:core:canonical` | golden-vectors identity profile, Q-011 |
| Resource conservation | `:core:resource`, `:runtime:governor` | Q-004, Q-012, CORE-RESOURCE |
| FSM illegal edges | `:core:state` | DATA-STATES, INV-018 |
| Commit reconcile | `:core:state`, `:core:contracts` | runtime-recovery-fixtures RR-001..007 |
| Idempotency claim | `:runtime:request-registry`, `:data:persistence` | command-conformance-fixtures, ADR-004/005 |
| Orchestrator fallback | `:runtime:orchestrator`, `:features:routing` | FEAT-ROUTING, CORE-ORCHESTRATOR |
| OpenAPI route smoke | `:interfaces:http` | omnillm.openapi.yaml, ADR-011 |
| Feature packs / engines | `:features:*`, `:engines:*` | feature + engine pack unit tests |

It does **not** run Android instrumentation (`androidTest`) or full APK assemble.

## Related verification tasks

```bash
./gradlew checkContractDrift          # specs vs generated Kotlin drift
./gradlew checkModuleDependencyRules  # INV-001 / ADR-010 / ADR-007 module edges
./gradlew checkNative16kb             # ELF 16 KB LOAD alignment scan
./gradlew check                       # root check (drift + deps + 16kb)
./gradlew generateContracts           # regenerate from specs/
```

### Automated CI parity

GitHub Actions [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs the full gate
(contract drift, `./gradlew test`, lint, assemble, 16 KB). For the same sequence locally:

```bash
bash tools/ci/local_ci.sh              # Unix / WSL / Git Bash
# or
.\tools\ci\local_ci.ps1                # Windows PowerShell
.\tools\ci\local_ci.ps1 -SkipAssemble  # JVM + drift only
```

Details and release signing secrets: [`tools/ci/README.md`](../../tools/ci/README.md).

## Mapping to quality scenarios (selected)

| ID | Statement (abbrev) | Primary tests |
|---|---|---|
| Q-003 | commit reply loss → one durable outcome | `CommitReconcileFixtureTest`, `CommandConformanceFixturesTest` |
| Q-004 | request terminal preserves resident accounting | `ResourceConservationPropertyTest`, `ResourceGovernorTest` |
| Q-011 | canonical identities match golden vectors | `GoldenIdentityEncodingTest`, `IdentityNegativeVectorsTest` |
| Q-012 | resource limit admissible on every dimension | `ResourceConservationPropertyTest` |
| Q-018 | contract projections consistent | OpenAPI/AIDL claim shape + catalog error tests |

Evidence status for packaged design scenarios remains `NOT_EXECUTED` until release
evidence packages are produced; these unit tests are implementation gates, not
product evidence packages.

## Adding tests

1. Prefer pure unit tests under `src/test/kotlin` in the owning module.
2. Do not invent types/enums/states/errors absent from `specs/`.
3. For recovery / claim semantics, cite fixture IDs (`RR-00x`, command-conformance
   bullets) in the test KDoc.
4. Keep UI / Android process tests out of JVM modules (INV-001).
