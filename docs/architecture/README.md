# Implementation architecture notes

Product normative architecture lives in the product document package (`docs/20-architecture/`, ADRs, invariants).

This directory holds **implementation-facing** notes for the Android monorepo (module wiring, process map, build locks) that should not fork product authority.

- Module map & dependency rules: repository root [`AGENTS.md`](../../AGENTS.md)
- Toolchain lock: [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml)
- Specs authority: [`specs/`](../../specs/)
- Contract integration (OpenAPI / AIDL / SQL single-writer): [`contract-integration.md`](./contract-integration.md)
- Unit / contract tests (`./gradlew test`): [`testing.md`](./testing.md)
- Engine Registry attach + selection policy: [`engine-registry-attachment.md`](./engine-registry-attachment.md)
- Product-complete adversarial audit (INV-001 / ADR-010): [`audit-product-complete.md`](./audit-product-complete.md)
- Adversarial product-complete audit (assemble + CI): [`audit-product-complete.md`](./audit-product-complete.md)
