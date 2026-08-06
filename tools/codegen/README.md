# Contract codegen

Generates Kotlin formal-contract sources from machine-readable `specs/` catalogs.

## Authority

- Product: `docs/30-core-platform/formal-contract-artifacts.md` (drift rule: generated code only from catalogs; source authority wins).
- Inputs:
  - `specs/canonical-types.yaml`
  - `specs/state-machines.yaml`
  - `specs/error-catalog.yaml`
  - `specs/access-control-catalog.yaml`
  - `specs/capability-catalog.yaml`
  - (tests) `specs/golden-vectors/canonical-encoding.yaml`

## Outputs

| Catalog | Output package |
|---|---|
| canonical-types + ACL + capability | `core/canonical/.../generated/` |
| state-machines | `core/state/.../generated/` |
| error-catalog | `core/errors/.../generated/` |

All generated files start with `// GENERATED FILE — DO NOT EDIT BY HAND`.

## Commands

```bash
# From repo root
pip install -r tools/codegen/requirements.txt

python tools/codegen/generate_contracts.py
# or
./gradlew generateContracts
# alias
./gradlew toolsCodegen

# CI drift gate (must fail when catalogs and committed generated sources diverge)
python tools/codegen/generate_contracts.py --check
./gradlew checkContractDrift
./gradlew check
```

Python override: `-Pomnillm.python=C:/Path/to/python.exe`

## CI note — drift must fail the build

Hand-written prose or ad-hoc Kotlin **must not** invent types, enums, states, scopes, or error codes absent from catalogs.

The build gate:

1. `checkContractDrift` re-runs the generator into a temp tree and diffs against committed `*/generated/*.kt`.
2. Any difference fails the build; fix by re-running `./gradlew generateContracts` and reviewing the catalog-driven delta.
3. Root `check` and JVM module `check` tasks depend on this gate.
4. `:core:canonical|state|errors:compileKotlin` depend on `generateContracts` (Gradle 9 validates that generated dirs are task outputs).

**CI order matters:** run `checkContractDrift` on the checked-out tree **before** (or without) regenerating and committing, so a PR that only edits YAML but forgets regenerated Kotlin fails. After a catalog change, the author must run `generateContracts` and commit the updated `generated/` sources.

Do **not** edit generated files to “make CI green” — change the YAML authority, then regenerate.

## Tests

```bash
./gradlew :core:canonical:test :core:state:test :core:errors:test
```

- Golden vectors: `ArtifactPackageId` / `ModelRevisionId` / `BlobId` encoding.
- FSM: illegal transitions rejected; multi-`from` expansion; ambiguous guard-resolved edges.
- Error catalog: HTTP status + retryable projection from golden vectors.
